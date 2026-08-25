package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactPublisher;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.ReplayIndexWriter;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.SeekPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Publishes one sealed recording exactly once and owns its terminal catalog
 * transition.
 *
 * <p>The storage publisher is intentionally kept behind this boundary. The
 * finalizer therefore controls the ordering between the database lifecycle,
 * local index creation, manifest publication and the final {@code AVAILABLE}
 * transition.</p>
 */
final class ReplayFinalizer implements RecordingCoordinator.FinalizationHandler {

    private final ReplayRepository replayRepository;
    private final ReplayArtifactPublisher artifactPublisher;
    private final ReplayIndexWriter indexWriter;
    private final ReplayAdapter adapter;
    private final RecordingLeaseManager leaseManager;
    private final Executor executor;
    private final Clock clock;
    private final ConcurrentHashMap<RecordingSessionId, CompletableFuture<RecordingSession>>
            finalizations = new ConcurrentHashMap<>();

    ReplayFinalizer(
            ReplayRepository replayRepository,
            ReplayArtifactPublisher artifactPublisher,
            ReplayIndexWriter indexWriter,
            ReplayAdapter adapter,
            RecordingLeaseManager leaseManager,
            Executor executor,
            Clock clock) {
        this.replayRepository = Objects.requireNonNull(replayRepository, "replayRepository");
        this.artifactPublisher = Objects.requireNonNull(artifactPublisher, "artifactPublisher");
        this.indexWriter = Objects.requireNonNull(indexWriter, "indexWriter");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<RecordingSession> finalize(
            DefaultRecordingSession session,
            ReplayCompletionReason reason) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(reason, "reason");
        if (session.status() == RecordingStatus.AVAILABLE
                || session.status() == RecordingStatus.FAILED) {
            return CompletableFuture.completedFuture(session);
        }

        CompletableFuture<RecordingSession> created = new CompletableFuture<>();
        CompletableFuture<RecordingSession> existing = finalizations.putIfAbsent(session.id(), created);
        if (existing != null) {
            return existing;
        }

        try {
            executor.execute(() -> begin(session, reason, created));
        } catch (Throwable failure) {
            finalizations.remove(session.id(), created);
            fail(session, created, ReplayFailureCode.INTERNAL_ERROR, "finalizer scheduling", failure);
        }
        return created;
    }

    private void begin(
            DefaultRecordingSession session,
            ReplayCompletionReason reason,
            CompletableFuture<RecordingSession> result) {
        if (session.status() == RecordingStatus.AVAILABLE
                || session.status() == RecordingStatus.FAILED) {
            complete(session, result);
            return;
        }
        if (session.status() != RecordingStatus.FINALIZING) {
            fail(session, result, ReplayFailureCode.INTERNAL_ERROR,
                    "finalizer session state", new IllegalStateException(
                            "finalizer requires FINALIZING, got " + session.status()));
            return;
        }

        Optional<RecordingSegmentAppender.RecordingArtifacts> sealed = session.sealedArtifacts();
        if (sealed.isEmpty()) {
            fail(session, result, ReplayFailureCode.CORRUPT_DATA,
                    "sealed recording artifacts", new IllegalStateException("artifacts are missing"));
            return;
        }
        RecordingSegmentAppender.RecordingArtifacts artifacts = sealed.orElseThrow();
        if (artifacts.completionReason().isPresent()
                && artifacts.completionReason().orElseThrow() != reason) {
            fail(session, result, ReplayFailureCode.CORRUPT_DATA,
                    "completion reason", new IllegalArgumentException(
                            "sealed completion reason does not match finalizer reason"));
            return;
        }

        final CompletionStage<Optional<ReplayRepository.ReplayRow>> rowStage;
        try {
            rowStage = Objects.requireNonNull(
                    replayRepository.find(session.replayId()),
                    "replay repository find result");
        } catch (Throwable failure) {
            fail(session, result, ReplayFailureCode.DATABASE_ERROR, "load replay row", failure);
            return;
        }
        rowStage.whenCompleteAsync((row, failure) -> {
            if (failure != null) {
                fail(session, result, ReplayFailureCode.DATABASE_ERROR, "load replay row", unwrap(failure));
                return;
            }
            if (row == null || row.isEmpty()) {
                fail(session, result, ReplayFailureCode.DATABASE_ERROR,
                        "load replay row", new ReplayRepository.ReplayNotFoundException(session.replayId()));
                return;
            }
            prepare(session, reason, artifacts, row.orElseThrow(), result);
        }, executor);
    }

    private void prepare(
            DefaultRecordingSession session,
            ReplayCompletionReason reason,
            RecordingSegmentAppender.RecordingArtifacts artifacts,
            ReplayRepository.ReplayRow row,
            CompletableFuture<RecordingSession> result) {
        AdapterDescriptor descriptor;
        try {
            descriptor = Objects.requireNonNull(adapter.descriptor(), "adapter.descriptor");
        } catch (Throwable failure) {
            fail(session, result, ReplayFailureCode.INCOMPATIBLE_ADAPTER,
                    "adapter descriptor", failure);
            return;
        }
        if (!descriptor.adapterId().equals(row.adapterId())
                || descriptor.protocolVersion() != row.protocolVersion()
                || descriptor.adapterFormatRevision() != row.formatRevision()) {
            fail(session, result, ReplayFailureCode.INCOMPATIBLE_ADAPTER,
                    "adapter compatibility", new IllegalStateException("catalog adapter differs"));
            return;
        }

        if (row.status() == RecordingStatus.AVAILABLE) {
            session.markAvailable();
            complete(session, result);
            return;
        }
        if (row.status() == RecordingStatus.FAILED || row.status() == RecordingStatus.DELETING) {
            session.markFinalizerFailed(
                    ReplayFailureCode.DATABASE_ERROR,
                    "recording is already terminal in the replay catalog");
            complete(session, result);
            return;
        }

        CompletionStage<Void> lifecycle = ensureFinalizing(session.replayId(), row);
        lifecycle.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                fail(session, result, ReplayFailureCode.DATABASE_ERROR,
                        "recording finalizing transition", unwrap(failure));
                return;
            }
            prepareArtifacts(session, reason, artifacts, descriptor, result);
        }, executor);
    }

    private CompletionStage<Void> ensureFinalizing(
            dev.voldechse.replayframework.api.id.ReplayId replayId,
            ReplayRepository.ReplayRow row) {
        return switch (row.status()) {
            case RECORDING -> transitionToFinalizing(replayId);
            case FINALIZING -> CompletableFuture.completedFuture(null);
            case AVAILABLE, FAILED, DELETING -> CompletableFuture.completedFuture(null);
            case INITIALIZING -> CompletableFuture.failedFuture(
                    new IllegalStateException("replay is still initializing"));
        };
    }

    private CompletionStage<Void> transitionToFinalizing(
            dev.voldechse.replayframework.api.id.ReplayId replayId) {
        final CompletionStage<ReplayRepository.ReplayRow> transition;
        try {
            transition = Objects.requireNonNull(
                    replayRepository.transition(new ReplayRepository.ReplayTransition(
                            replayId,
                            RecordingStatus.RECORDING,
                            RecordingStatus.FINALIZING,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty())),
                    "recording finalizing transition result");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return transition.thenApply(ignored -> null);
    }

    private void prepareArtifacts(
            DefaultRecordingSession session,
            ReplayCompletionReason reason,
            RecordingSegmentAppender.RecordingArtifacts artifacts,
            AdapterDescriptor descriptor,
            CompletableFuture<RecordingSession> result) {
        CompletableFuture.supplyAsync(
                        () -> prepareArtifactsSynchronously(session, artifacts),
                        executor)
                .whenCompleteAsync((prepared, failure) -> {
                    if (failure != null) {
                        ReplayFailureCode code = unwrap(failure) instanceof StorageBudgetException
                                ? ReplayFailureCode.STORAGE_ERROR
                                : ReplayFailureCode.CORRUPT_DATA;
                        fail(session, result, code,
                                "replay artifact validation", unwrap(failure));
                        return;
                    }
                    publish(session, reason, artifacts, descriptor, prepared, result);
                }, executor);
    }

    private PreparedArtifacts prepareArtifactsSynchronously(
            DefaultRecordingSession session,
            RecordingSegmentAppender.RecordingArtifacts artifacts) {
        List<Path> checkpoints = artifacts.checkpointFiles();
        if (checkpoints.isEmpty() || checkpoints.size() != artifacts.checkpointCount()) {
            throw new ArtifactValidationException("checkpoint artifact count is inconsistent");
        }
        if (artifacts.segmentFiles().size() != artifacts.segmentCount()) {
            throw new ArtifactValidationException("segment artifact count is inconsistent");
        }

        Path initialCheckpoint = validateArtifactList(
                checkpoints, "checkpoints", "checkpoint", session.replayId());
        Path workspace = requireWorkspace(initialCheckpoint);
        validateArtifactList(checkpoints, "checkpoints", "checkpoint", session.replayId(), workspace);
        validateArtifactList(artifacts.segmentFiles(), "segments", "segment", session.replayId(), workspace);
        validateSeekPoints(artifacts.seekPoints(), artifacts.segmentFiles().size(), checkpoints.size());

        Path indexFile = workspace.resolve("index.bin").normalize();
        if (!indexFile.startsWith(workspace)
                || Files.exists(indexFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactValidationException("index.bin already exists or escapes workspace");
        }
        try {
            indexWriter.write(indexFile, ReplayIndex.of(artifacts.seekPoints()));
            long bytes = totalBytes(artifacts.segmentFiles(), artifacts.checkpointFiles(), indexFile);
            if (session.request().budget().maxPendingUploadBytes().isPresent()
                    && bytes > session.request().budget().maxPendingUploadBytes().getAsLong()) {
                throw new StorageBudgetException("pending upload budget exceeded");
            }
        } catch (IOException | ArithmeticException failure) {
            throw new ArtifactValidationException("could not create replay index", failure);
        }
        return new PreparedArtifacts(workspace, indexFile);
    }

    private static long totalBytes(List<Path> segmentFiles, List<Path> checkpointFiles, Path indexFile)
            throws IOException {
        long bytes = 0L;
        for (Path source : segmentFiles) {
            bytes = Math.addExact(bytes, Files.size(source));
        }
        for (Path source : checkpointFiles) {
            bytes = Math.addExact(bytes, Files.size(source));
        }
        return Math.addExact(bytes, Files.size(indexFile));
    }

    private void publish(
            DefaultRecordingSession session,
            ReplayCompletionReason reason,
            RecordingSegmentAppender.RecordingArtifacts artifacts,
            AdapterDescriptor descriptor,
            PreparedArtifacts prepared,
            CompletableFuture<RecordingSession> result) {
        ReplayArtifactPublisher.PublishRequest request;
        try {
            request = new ReplayArtifactPublisher.PublishRequest(
                    session.replayId(),
                    descriptor.adapterId(),
                    descriptor.protocolVersion(),
                    descriptor.registryFingerprint(),
                    descriptor.adapterFormatRevision(),
                    artifacts.durationNanos(),
                    prepared.indexFile(),
                    artifacts.segmentFiles(),
                    artifacts.checkpointFiles());
        } catch (Throwable failure) {
            fail(session, result, ReplayFailureCode.CORRUPT_DATA,
                    "publisher request", failure);
            return;
        }

        final CompletionStage<ReplayManifest> publishStage;
        try {
            publishStage = Objects.requireNonNull(
                    artifactPublisher.publish(request), "artifact publisher result");
        } catch (Throwable failure) {
            fail(session, result, ReplayFailureCode.STORAGE_ERROR,
                    "replay artifact publish", failure);
            return;
        }
        publishStage.whenCompleteAsync((manifest, failure) -> {
            if (failure != null) {
                fail(session, result, ReplayFailureCode.STORAGE_ERROR,
                        "replay artifact publish", unwrap(failure));
                return;
            }
            try {
                validateManifest(manifest, session, artifacts, descriptor);
            } catch (Throwable validationFailure) {
                fail(session, result, ReplayFailureCode.CORRUPT_DATA,
                        "published replay manifest", validationFailure);
                return;
            }
            persistAvailable(session, reason, artifacts, result);
        }, executor);
    }

    private void persistAvailable(
            DefaultRecordingSession session,
            ReplayCompletionReason reason,
            RecordingSegmentAppender.RecordingArtifacts artifacts,
            CompletableFuture<RecordingSession> result) {
        final CompletionStage<ReplayRepository.ReplayRow> transition;
        try {
            transition = Objects.requireNonNull(
                    replayRepository.transition(new ReplayRepository.ReplayTransition(
                            session.replayId(),
                            RecordingStatus.FINALIZING,
                            RecordingStatus.AVAILABLE,
                            Optional.empty(),
                            Optional.of(clock.instant()),
                            Optional.of(reason),
                            Optional.empty(),
                            Optional.of(new ReplayRepository.ReplayMetrics(
                                    artifacts.durationNanos(),
                                    artifacts.payloadBytes(),
                                    artifacts.packetCount(),
                                    artifacts.segmentCount(),
                                    artifacts.checkpointCount())))),
                    "available transition result");
        } catch (Throwable failure) {
            fail(session, result, ReplayFailureCode.DATABASE_ERROR,
                    "available transition", failure);
            return;
        }
        transition.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                fail(session, result, ReplayFailureCode.DATABASE_ERROR,
                        "available transition", unwrap(failure));
                return;
            }
            session.markAvailable();
            try {
                leaseManager.release(session.replayId()).whenComplete((released, releaseFailure) -> {
                    // AVAILABLE is already committed; a release diagnostic cannot revoke it.
                });
            } catch (Throwable ignoredReleaseFailure) {
                // AVAILABLE is already committed; release remains best effort.
            }
            complete(session, result);
        }, executor);
    }

    private void fail(
            DefaultRecordingSession session,
            CompletableFuture<RecordingSession> result,
            ReplayFailureCode code,
            String stage,
            Throwable cause) {
        String description = diagnostic(session, stage, cause);
        transitionToFailed(session.replayId(), code, description)
                .whenCompleteAsync((ignored, transitionFailure) -> {
                    session.markFinalizerFailed(code, description);
                    try {
                        leaseManager.release(session.replayId()).whenComplete((released, releaseFailure) -> {
                            // The failure state is already local and persisted best effort.
                        });
                    } catch (Throwable ignoredReleaseFailure) {
                        // Failure handling must not mask the original finalizer problem.
                    }
                    finalizations.remove(session.id(), result);
                    if (transitionFailure != null) {
                        result.completeExceptionally(unwrap(transitionFailure));
                    } else {
                        result.complete(session);
                    }
                }, executor);
    }

    private CompletionStage<ReplayRepository.ReplayRow> transitionToFailed(
            dev.voldechse.replayframework.api.id.ReplayId replayId,
            ReplayFailureCode code,
            String description) {
        final CompletionStage<Optional<ReplayRepository.ReplayRow>> find;
        try {
            find = Objects.requireNonNull(replayRepository.find(replayId), "replay repository find result");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return find.thenCompose(row -> {
            if (row.isEmpty()) {
                return CompletableFuture.failedFuture(new ReplayRepository.ReplayNotFoundException(replayId));
            }
            ReplayRepository.ReplayRow current = row.orElseThrow();
            if (current.status() == RecordingStatus.AVAILABLE
                    || current.status() == RecordingStatus.FAILED
                    || current.status() == RecordingStatus.DELETING) {
                return CompletableFuture.completedFuture(current);
            }
            try {
                return replayRepository.transition(new ReplayRepository.ReplayTransition(
                        replayId,
                        current.status(),
                        RecordingStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new ReplayRepository.FailureDetails(code, description, clock.instant())),
                        Optional.empty()));
            } catch (ReplayRepository.StatusTransitionConflictException conflict) {
                return replayRepository.find(replayId).thenCompose(latest -> latest
                        .map(CompletableFuture::completedFuture)
                        .orElseGet(() -> CompletableFuture.failedFuture(conflict)));
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
    }

    private void validateManifest(
            ReplayManifest manifest,
            DefaultRecordingSession session,
            RecordingSegmentAppender.RecordingArtifacts artifacts,
            AdapterDescriptor descriptor) {
        Objects.requireNonNull(manifest, "artifact publisher returned null manifest");
        if (!session.replayId().toString().equals(manifest.replayId())
                || !descriptor.adapterId().equals(manifest.adapterId())
                || descriptor.protocolVersion() != manifest.protocolVersion()
                || !descriptor.registryFingerprint().equals(manifest.registryFingerprint())
                || descriptor.adapterFormatRevision() != manifest.formatRevision()
                || artifacts.durationNanos() != manifest.durationNanos()) {
            throw new ArtifactValidationException("published manifest metadata does not match recording");
        }
    }

    private static Path validateArtifactList(
            List<Path> sources,
            String directory,
            String extension,
            dev.voldechse.replayframework.api.id.ReplayId replayId) {
        Path first = null;
        for (int index = 0; index < sources.size(); index++) {
            Path normalized = validateArtifact(sources.get(index), null, directory, extension, index, replayId);
            if (first == null) {
                first = normalized;
            }
        }
        return first;
    }

    private static void validateArtifactList(
            List<Path> sources,
            String directory,
            String extension,
            dev.voldechse.replayframework.api.id.ReplayId replayId,
            Path workspace) {
        for (int index = 0; index < sources.size(); index++) {
            validateArtifact(sources.get(index), workspace, directory, extension, index, replayId);
        }
    }

    private static Path validateArtifact(
            Path source,
            Path workspace,
            String directory,
            String extension,
            int index,
            dev.voldechse.replayframework.api.id.ReplayId replayId) {
        if (source == null) {
            throw new ArtifactValidationException("artifact path is null");
        }
        Path normalized = source.toAbsolutePath().normalize();
        Path expectedName = Path.of(String.format("%08d.%s", index, extension));
        if (!expectedName.equals(normalized.getFileName())
                || normalized.getParent() == null
                || !directory.equals(normalized.getParent().getFileName().toString())) {
            throw new ArtifactValidationException("artifact ordinal or directory is invalid for " + replayId);
        }
        if (workspace != null && !normalized.startsWith(workspace)) {
            throw new ArtifactValidationException("artifact escapes recording workspace");
        }
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactValidationException("artifact is missing or is not a regular file");
        }
        return normalized;
    }

    private static Path requireWorkspace(Path initialCheckpoint) {
        Path checkpoints = initialCheckpoint.getParent();
        Path workspace = checkpoints == null ? null : checkpoints.getParent();
        if (workspace == null
                || !"checkpoints".equals(checkpoints.getFileName().toString())
                || !initialCheckpoint.getFileName().toString().equals("00000000.checkpoint")) {
            throw new ArtifactValidationException("initial checkpoint does not define a workspace");
        }
        return workspace.toAbsolutePath().normalize();
    }

    private static void validateSeekPoints(
            List<SeekPoint> seekPoints,
            int segmentCount,
            int checkpointCount) {
        if (seekPoints.isEmpty() || !seekPoints.getFirst().equals(new SeekPoint(0L, 0, 0, 0L))) {
            throw new ArtifactValidationException("seek index does not contain the initial checkpoint");
        }
        ReplayIndex.of(seekPoints);
        for (SeekPoint point : seekPoints) {
            if (point.checkpointOrdinal() >= checkpointCount
                    || (segmentCount == 0
                    ? !point.equals(seekPoints.getFirst())
                    : point.segmentOrdinal() >= segmentCount)) {
                throw new ArtifactValidationException("seek point references a missing artifact");
            }
        }
    }

    private void complete(
            DefaultRecordingSession session,
            CompletableFuture<RecordingSession> result) {
        finalizations.remove(session.id(), result);
        result.complete(session);
    }

    private static String diagnostic(
            DefaultRecordingSession session,
            String stage,
            Throwable cause) {
        return "recording " + stage + " failed; replay=" + session.replayId()
                + ", session=" + session.id()
                + ", cause=" + cause.getClass().getSimpleName();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record PreparedArtifacts(Path workspace, Path indexFile) {
    }

    private static final class ArtifactValidationException extends RuntimeException {
        private ArtifactValidationException(String message) {
            super(message);
        }

        private ArtifactValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class StorageBudgetException extends RuntimeException {
        private StorageBudgetException(String message) {
            super(message);
        }
    }
}
