package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves expired recording leases during runtime startup.
 *
 * <p>Recovery is deliberately conservative: a published manifest is recorded
 * as diagnostic evidence, but an expired active lease is never promoted to an
 * available replay because the recording process may have stopped before its
 * final database commit.</p>
 */
public final class RecordingRecoveryService {

    private final LeaseRepository leaseRepository;
    private final ReplayRepository replayRepository;
    private final ReplayArtifactReader artifactReader;
    private final Clock clock;
    private final Executor executor;
    private final AtomicReference<CompletableFuture<RecoveryReport>> running = new AtomicReference<>();

    public RecordingRecoveryService(
            LeaseRepository leaseRepository,
            ReplayRepository replayRepository,
            ReplayArtifactReader artifactReader,
            Clock clock,
            Executor executor) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.artifactReader = Objects.requireNonNull(artifactReader, "artifactReader");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Runs one serialized startup recovery pass for the supplied instant. */
    public CompletionStage<RecoveryReport> recoverExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        CompletableFuture<RecoveryReport> created = new CompletableFuture<>();
        CompletableFuture<RecoveryReport> existing = running.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        if (!running.compareAndSet(existing, created)) {
            CompletableFuture<RecoveryReport> concurrent = running.get();
            return concurrent == null ? recoverExpired(now) : concurrent;
        }

        final CompletionStage<List<LeaseRepository.LeaseRow>> expired;
        try {
            expired = Objects.requireNonNull(
                    leaseRepository.findExpired(now), "lease repository expired result");
        } catch (Throwable failure) {
            running.compareAndSet(created, null);
            created.completeExceptionally(failure);
            return created;
        }
        expired.whenCompleteAsync((rows, failure) -> {
            if (failure != null) {
                finish(created, null, unwrap(failure));
                return;
            }
            if (rows == null) {
                finish(created, null, new IllegalStateException("lease repository returned null"));
                return;
            }
            RecoveryCounters counters = new RecoveryCounters(rows.size());
            process(rows, 0, counters, created);
        }, executor);
        return created;
    }

    private void process(
            List<LeaseRepository.LeaseRow> rows,
            int index,
            RecoveryCounters counters,
            CompletableFuture<RecoveryReport> result) {
        if (index == rows.size()) {
            finish(result, counters.report(), null);
            return;
        }
        processOne(rows.get(index), counters).whenCompleteAsync(
                (ignored, failure) -> process(rows, index + 1, counters, result), executor);
    }

    private CompletionStage<Void> processOne(
            LeaseRepository.LeaseRow lease,
            RecoveryCounters counters) {
        final CompletionStage<Optional<ReplayRepository.ReplayRow>> rowStage;
        try {
            rowStage = Objects.requireNonNull(
                    replayRepository.find(lease.replayId()), "replay repository find result");
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(null);
        }
        return rowStage.handle((row, failure) -> failure == null ? row : Optional.<ReplayRepository.ReplayRow>empty())
                .thenCompose(row -> {
                    if (row.isEmpty()) {
                        return release(lease);
                    }
                    ReplayRepository.ReplayRow replay = row.orElseThrow();
                    if (replay.status() == RecordingStatus.AVAILABLE
                            || replay.status() == RecordingStatus.FAILED
                            || replay.status() == RecordingStatus.DELETING) {
                        counters.alreadyTerminal++;
                        return release(lease);
                    }
                    return inspectManifest(lease.replayId(), counters)
                            .thenCompose(ignored -> failActiveRow(replay, counters))
                            .thenCompose(ignored -> release(lease));
                });
    }

    private CompletionStage<Void> inspectManifest(ReplayId replayId, RecoveryCounters counters) {
        final CompletionStage<ReplayArtifactReader.VerifiedReplay> manifest;
        try {
            manifest = Objects.requireNonNull(
                    artifactReader.openVerified(replayId), "artifact reader result");
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(null);
        }
        return manifest.handle((verified, failure) -> {
            if (failure == null && verified != null) {
                counters.publishedManifestDiagnostics++;
            }
            return null;
        });
    }

    private CompletionStage<Void> failActiveRow(
            ReplayRepository.ReplayRow replay,
            RecoveryCounters counters) {
        final CompletionStage<ReplayRepository.ReplayRow> transition;
        try {
            transition = Objects.requireNonNull(
                    replayRepository.transition(new ReplayRepository.ReplayTransition(
                            replay.replayId(),
                            replay.status(),
                            RecordingStatus.FAILED,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(new ReplayRepository.FailureDetails(
                                    ReplayFailureCode.SERVER_CRASH,
                                    "recording recovery found an expired lease; replay="
                                            + replay.replayId(),
                                    clock.instant())),
                            Optional.empty())),
                    "replay failure transition result");
        } catch (ReplayRepository.StatusTransitionConflictException conflict) {
            counters.failedRecordings++;
            return CompletableFuture.completedFuture(null);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(null);
        }
        return transition.handle((ignored, failure) -> {
            if (failure == null || unwrap(failure) instanceof ReplayRepository.StatusTransitionConflictException) {
                counters.failedRecordings++;
            }
            return null;
        });
    }

    private CompletionStage<Void> release(LeaseRepository.LeaseRow lease) {
        try {
            return Objects.requireNonNull(
                    leaseRepository.release(lease.replayId(), lease.runtimeInstanceId()),
                    "lease repository release result")
                    .handle((ignored, failure) -> null);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private void finish(
            CompletableFuture<RecoveryReport> result,
            RecoveryReport report,
            Throwable failure) {
        running.compareAndSet(result, null);
        if (failure == null) {
            result.complete(report);
        } else {
            result.completeExceptionally(failure);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Immutable summary of one startup recovery pass. */
    public record RecoveryReport(
            int expiredLeases,
            int failedRecordings,
            int alreadyTerminal,
            int publishedManifestDiagnostics) {
        public RecoveryReport {
            if (expiredLeases < 0 || failedRecordings < 0 || alreadyTerminal < 0
                    || publishedManifestDiagnostics < 0) {
                throw new IllegalArgumentException("recovery counters must not be negative");
            }
        }
    }

    private static final class RecoveryCounters {
        private final int expiredLeases;
        private int failedRecordings;
        private int alreadyTerminal;
        private int publishedManifestDiagnostics;

        private RecoveryCounters(int expiredLeases) {
            this.expiredLeases = expiredLeases;
        }

        private RecoveryReport report() {
            return new RecoveryReport(
                    expiredLeases,
                    failedRecordings,
                    alreadyTerminal,
                    publishedManifestDiagnostics);
        }
    }
}
