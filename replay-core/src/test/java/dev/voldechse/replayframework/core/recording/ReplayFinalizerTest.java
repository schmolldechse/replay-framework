package dev.voldechse.replayframework.core.recording;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.CapturePolicy;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.core.artifact.ArtifactIntegrityVerifier;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactPublisher;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.ReplayIndexReader;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplayManifestCodec;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.local.LocalReplayStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReplayFinalizerTest {

    @Test
    void publishesManifestBeforeCatalogAvailable(@TempDir Path temporaryDirectory) throws Exception {
        FinalizerFixture fixture = new FinalizerFixture(temporaryDirectory);
        try {
            RecordingSession session = fixture.start();
            RecordingSession completed = session.stop().toCompletableFuture().join();

            assertEquals(
                    RecordingStatus.AVAILABLE,
                    completed.status(),
                    completed.failureCode() + " / " + completed.failureDescription()
                            + " / transitions=" + fixture.repository.transitions);
            assertEquals(RecordingStatus.AVAILABLE, fixture.repository.row().status());
            assertEquals(ReplayCompletionReason.MANUAL,
                    fixture.repository.row().completionReason().orElseThrow());
            assertTrue(fixture.storage.exists(
                    fixture.replayId,
                    ArtifactKey.of("manifest.json")).toCompletableFuture().join());
            assertEquals(List.of(
                    RecordingStatus.RECORDING,
                    RecordingStatus.FINALIZING,
                    RecordingStatus.AVAILABLE), fixture.repository.transitions);
            assertEquals(List.of(new SeekPoint(0L, 0, 0, 0L)),
                    new ReplayIndexReader().read(fixture.workspace.resolve("index.bin")).points());
        } finally {
            fixture.close();
        }
    }

    @Test
    void pendingUploadBudgetFailureKeepsSealedArtifacts(@TempDir Path temporaryDirectory)
            throws Exception {
        FinalizerFixture fixture = new FinalizerFixture(temporaryDirectory);
        try {
            RecordingSession session = fixture.start(
                    dev.voldechse.replayframework.api.recording.ReplayBudget.builder()
                            .maxSegmentBytes(64L)
                            .maxQueueBytes(1024L)
                            .maxPendingUploadBytes(1L)
                            .build());
            RecordingSession completed = session.stop().toCompletableFuture().join();

            assertEquals(RecordingStatus.FAILED, completed.status());
            assertEquals(ReplayFailureCode.STORAGE_ERROR,
                    completed.failureCode().orElseThrow());
            assertEquals(RecordingStatus.FAILED, fixture.repository.row().status());
            assertFalse(fixture.storage.exists(
                    fixture.replayId,
                    ArtifactKey.of("manifest.json")).toCompletableFuture().join());
            assertTrue(Files.isRegularFile(
                    fixture.workspace.resolve("checkpoints/00000000.checkpoint")));
        } finally {
            fixture.close();
        }
    }

    @Test
    void recoveryFailsExpiredActiveRowsWithoutPromotingThem(@TempDir Path temporaryDirectory) {
        RecoveryLeaseFixture leases = new RecoveryLeaseFixture();
        RecoveryCatalogFixture catalog = new RecoveryCatalogFixture();
        ReplayId initializing = ReplayId.parse("55555555-5555-5555-5555-555555555551");
        ReplayId recording = ReplayId.parse("55555555-5555-5555-5555-555555555552");
        ReplayId finalizing = ReplayId.parse("55555555-5555-5555-5555-555555555553");
        ReplayId available = ReplayId.parse("55555555-5555-5555-5555-555555555554");
        ReplayId missing = ReplayId.parse("55555555-5555-5555-5555-555555555555");
        leases.rows.add(lease(initializing));
        leases.rows.add(lease(recording));
        leases.rows.add(lease(finalizing));
        leases.rows.add(lease(available));
        leases.rows.add(lease(missing));
        catalog.rows.put(initializing, row(initializing, RecordingStatus.INITIALIZING));
        catalog.rows.put(recording, row(recording, RecordingStatus.RECORDING));
        catalog.rows.put(finalizing, row(finalizing, RecordingStatus.FINALIZING));
        catalog.rows.put(available, row(available, RecordingStatus.AVAILABLE));

        LocalReplayStorage storage = new LocalReplayStorage(
                temporaryDirectory.resolve("recovery-storage"), Runnable::run);
        ReplayArtifactReader reader = new ReplayArtifactReader(
                storage,
                new ReplayManifestCodec(new Gson()),
                new ArtifactIntegrityVerifier(),
                Runnable::run,
                temporaryDirectory.resolve("reader-work"));
        RecordingRecoveryService.RecoveryReport report = new RecordingRecoveryService(
                leases,
                catalog,
                reader,
                Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC),
                Runnable::run)
                .recoverExpired(Instant.parse("2026-08-25T10:00:00Z"))
                .toCompletableFuture()
                .join();

        assertEquals(5, report.expiredLeases());
        assertEquals(3, report.failedRecordings());
        assertEquals(1, report.alreadyTerminal());
        assertEquals(0, report.publishedManifestDiagnostics());
        assertEquals(3, catalog.failureTransitions.size());
        assertTrue(catalog.failureTransitions.stream()
                .allMatch(command -> command.failure().orElseThrow().code() == ReplayFailureCode.SERVER_CRASH));
        assertEquals(5, leases.released.size());
    }

    @Test
    void leaseAcquireHeartbeatAndReleaseUseRuntimeIdentity() throws Exception {
        LeaseFixture repository = new LeaseFixture();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        UUID runtimeInstanceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        RecordingLeaseManager manager = new RecordingLeaseManager(
                repository,
                Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(30),
                Duration.ofMillis(1),
                scheduler,
                Runnable::run,
                runtimeInstanceId);
        ReplayId replayId = ReplayId.parse("11111111-1111-1111-1111-111111111111");

        try {
            manager.acquire(replayId, (ignored, failure) -> {
            }).toCompletableFuture().join();
            assertTrue(repository.renewCalled.await(1, TimeUnit.SECONDS));
            repository.renewResult.complete(true);
            manager.release(replayId).toCompletableFuture().join();

            assertEquals(runtimeInstanceId, repository.acquireCommands.getFirst().runtimeInstanceId());
            assertEquals(runtimeInstanceId, repository.renewCommands.getFirst().runtimeInstanceId());
            assertEquals(runtimeInstanceId, repository.releasedRuntimeIds.getFirst());
        } finally {
            manager.close();
            scheduler.shutdownNow();
        }
    }

    private static final class LeaseFixture implements LeaseRepository {
        private final List<LeaseAcquire> acquireCommands = new CopyOnWriteArrayList<>();
        private final List<LeaseRenew> renewCommands = new CopyOnWriteArrayList<>();
        private final List<UUID> releasedRuntimeIds = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Boolean> renewResult = new CompletableFuture<>();
        private final CountDownLatch renewCalled = new CountDownLatch(1);

        @Override
        public CompletionStage<LeaseRow> acquire(LeaseAcquire command) {
            acquireCommands.add(command);
            return CompletableFuture.completedFuture(new LeaseRow(
                    command.replayId(),
                    command.runtimeInstanceId(),
                    command.acquiredAt(),
                    command.heartbeatAt(),
                    command.expiresAt(),
                    0L));
        }

        @Override
        public CompletionStage<Boolean> renew(LeaseRenew command) {
            renewCommands.add(command);
            renewCalled.countDown();
            return renewResult;
        }

        @Override
        public CompletionStage<Boolean> release(ReplayId replayId, UUID runtimeInstanceId) {
            releasedRuntimeIds.add(runtimeInstanceId);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<List<LeaseRow>> findExpired(Instant now) {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private static final class FinalizerFixture implements AutoCloseable {
        private ReplayId replayId;
        private final Path workspace;
        private final LocalReplayStorage storage;
        private final CatalogFixture repository = new CatalogFixture();
        private final LeaseFixture leaseRepository = new LeaseFixture();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final RecordingLeaseManager leaseManager;
        private final RecordingCoordinator coordinator;

        private FinalizerFixture(Path temporaryDirectory) throws IOException {
            workspace = temporaryDirectory.resolve("workspace");
            Files.createDirectories(workspace);
            storage = new LocalReplayStorage(temporaryDirectory.resolve("storage"), Runnable::run);
            PacketRegistry registry = PacketRegistry.of(List.of(new PacketDescriptor(
                    "play.clientbound.test_state",
                    PacketPhase.PLAY,
                    PacketDescriptor.Direction.CLIENTBOUND,
                    1,
                    PacketDisposition.STATEFUL,
                    true,
                    true,
                    true,
                    "test/state")));
            ReplayAdapter adapter = adapter(registry);
            leaseManager = new RecordingLeaseManager(
                    leaseRepository,
                    Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(10),
                    scheduler,
                    Runnable::run,
                    UUID.fromString("44444444-4444-4444-4444-444444444444"));
            ReplayArtifactPublisher publisher = new ReplayArtifactPublisher(
                    storage,
                    new ReplayManifestCodec(new Gson()),
                    new ArtifactIntegrityVerifier(),
                    Runnable::run,
                    temporaryDirectory.resolve("publisher-work"));
            ReplayFinalizer finalizer = new ReplayFinalizer(
                    repository,
                    publisher,
                    new dev.voldechse.replayframework.format.ReplayIndexWriter(),
                    adapter,
                    leaseManager,
                    Runnable::run,
                    Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC));
            AtomicReference<RecordingCoordinator> coordinatorRef = new AtomicReference<>();
            dev.voldechse.replayframework.core.capture.CaptureRouter router =
                    new dev.voldechse.replayframework.core.capture.CaptureRouter(
                            registry,
                            failure -> coordinatorRef.get().onAdapterFailure(failure),
                            (id, failure) -> coordinatorRef.get().onSinkFailure(id, failure));
            coordinator = new RecordingCoordinator(
                    adapter,
                    router,
                    repository,
                    (scope, participants) -> CompletableFuture.completedFuture(
                            new ResolvedRecordingScope(
                                    scope,
                                    1L,
                                    Set.of(),
                                    List.of(),
                                    CapturePolicy.builder().build(),
                                    participants,
                                    packet -> true)),
                    new RecordingCoordinator.RecordingTarget(
                            ReplayStorageBackend.LOCAL,
                            ReplayId::toString,
                            ignored -> workspace),
                    finalizer,
                    leaseManager,
                    Runnable::run);
            coordinatorRef.set(coordinator);
        }

        private RecordingSession start() {
            return start(dev.voldechse.replayframework.api.recording.ReplayBudget.builder()
                    .maxSegmentBytes(64L)
                    .maxQueueBytes(1024L)
                    .build());
        }

        private RecordingSession start(
                dev.voldechse.replayframework.api.recording.ReplayBudget budget) {
            RecordingSession session = coordinator.start(RecordingRequest.builder()
                    .title("finalizer test")
                    .description("finalizer test")
                    .scope(RecordingScope.builder().build())
                    .capturePolicy(CapturePolicy.builder().build())
                    .budget(budget)
                    .build()).toCompletableFuture().join();
            replayId = repository.row().replayId();
            return session;
        }

        @Override
        public void close() {
            leaseManager.close();
            scheduler.shutdownNow();
        }
    }

    private static ReplayAdapter adapter(PacketRegistry registry) {
        AdapterDescriptor descriptor = new AdapterDescriptor(
                "paper-26.2-test", 0, ReplayManifest.CURRENT_FORMAT_REVISION, registry.fingerprint());
        CaptureBridge bridge = new CaptureBridge() {
            @Override
            public void install(PacketSink sink, FailureHandler failureHandler) {
            }

            @Override
            public void uninstall() {
            }

            @Override
            public boolean installed() {
                return false;
            }
        };
        return new ReplayAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public PacketRegistry packetRegistry() {
                return registry;
            }

            @Override
            public CaptureBridge captureBridge() {
                return bridge;
            }

            @Override
            public CheckpointEncoder checkpointEncoder() {
                return request -> CompletableFuture.completedFuture(
                        new ReplayCheckpoint(0, request.elapsedNanos(), List.of()));
            }

            @Override
            public PlaybackBridge openPlayback(Player player) {
                return null;
            }
        };
    }

    private static final class CatalogFixture implements ReplayRepository {
        private final List<RecordingStatus> transitions = new CopyOnWriteArrayList<>();
        private ReplayRow row;

        @Override
        public synchronized CompletionStage<ReplayRow> create(ReplayCreate command) {
            row = new ReplayRow(
                    command.replayId(),
                    command.title(),
                    command.description(),
                    RecordingStatus.INITIALIZING,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    command.adapterId(),
                    command.protocolVersion(),
                    command.formatRevision(),
                    command.storageBackend(),
                    command.storageKey(),
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    command.createdAt(),
                    Optional.empty(),
                    Optional.empty(),
                    new JsonObject(),
                    0L,
                    0L);
            return CompletableFuture.completedFuture(row);
        }

        @Override
        public synchronized CompletionStage<Optional<ReplayRow>> find(ReplayId replayId) {
            return CompletableFuture.completedFuture(
                    row != null && row.replayId().equals(replayId) ? Optional.of(row) : Optional.empty());
        }

        @Override
        public CompletionStage<ReplayPage<ReplayRow>> page(ReplayQuery query) {
            return CompletableFuture.completedFuture(new ReplayPage<>(List.of(), Optional.empty()));
        }

        @Override
        public synchronized CompletionStage<ReplayRow> transition(ReplayTransition command) {
            if (row == null || !row.replayId().equals(command.replayId())
                    || row.status() != command.expectedStatus()) {
                return CompletableFuture.failedFuture(
                        new StatusTransitionConflictException(command.replayId(), command.expectedStatus()));
            }
            transitions.add(command.nextStatus());
            ReplayMetrics metrics = command.metrics().orElse(new ReplayMetrics(0L, 0L, 0L, 0L, 0L));
            row = new ReplayRow(
                    row.replayId(), row.title(), row.description(), command.nextStatus(),
                    command.completionReason(), command.failure().map(FailureDetails::code),
                    command.failure().map(FailureDetails::description), row.adapterId(),
                    row.protocolVersion(), row.formatRevision(), row.storageBackend(), row.storageKey(),
                    metrics.durationNanos(), metrics.totalBytes(), metrics.packetCount(),
                    metrics.segmentCount(), metrics.checkpointCount(), row.createdAt(),
                    command.startedAt().isPresent() ? command.startedAt() : row.startedAt(),
                    command.completedAt(), row.metadata(), row.metadataRevision(), row.entityVersion() + 1L);
            return CompletableFuture.completedFuture(row);
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            return CompletableFuture.completedFuture(null);
        }

        private synchronized ReplayRow row() {
            return row;
        }
    }

    private static LeaseRepository.LeaseRow lease(ReplayId replayId) {
        Instant time = Instant.parse("2026-08-25T09:00:00Z");
        return new LeaseRepository.LeaseRow(
                replayId,
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                time,
                time,
                Instant.parse("2026-08-25T09:00:30Z"),
                0L);
    }

    private static ReplayRepository.ReplayRow row(ReplayId replayId, RecordingStatus status) {
        return new ReplayRepository.ReplayRow(
                replayId,
                "recovery",
                "recovery",
                status,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "paper-26.2",
                0,
                ReplayManifest.CURRENT_FORMAT_REVISION,
                ReplayStorageBackend.LOCAL,
                replayId.toString(),
                0L,
                0L,
                0L,
                0L,
                0L,
                Instant.parse("2026-08-25T08:00:00Z"),
                Optional.empty(),
                Optional.empty(),
                new JsonObject(),
                0L,
                0L);
    }

    private static final class RecoveryLeaseFixture implements LeaseRepository {
        private final List<LeaseRow> rows = new CopyOnWriteArrayList<>();
        private final List<ReplayId> released = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<LeaseRow> acquire(LeaseAcquire command) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Boolean> renew(LeaseRenew command) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> release(ReplayId replayId, UUID runtimeInstanceId) {
            released.add(replayId);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<List<LeaseRow>> findExpired(Instant now) {
            return CompletableFuture.completedFuture(List.copyOf(rows));
        }
    }

    private static final class RecoveryCatalogFixture implements ReplayRepository {
        private final Map<ReplayId, ReplayRow> rows = new HashMap<>();
        private final List<ReplayTransition> failureTransitions = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<ReplayRow> create(ReplayCreate command) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public synchronized CompletionStage<Optional<ReplayRow>> find(ReplayId replayId) {
            return CompletableFuture.completedFuture(Optional.ofNullable(rows.get(replayId)));
        }

        @Override
        public CompletionStage<ReplayPage<ReplayRow>> page(ReplayQuery query) {
            return CompletableFuture.completedFuture(new ReplayPage<>(List.of(), Optional.empty()));
        }

        @Override
        public synchronized CompletionStage<ReplayRow> transition(ReplayTransition command) {
            ReplayRow current = rows.get(command.replayId());
            if (current == null || current.status() != command.expectedStatus()) {
                return CompletableFuture.failedFuture(
                        new StatusTransitionConflictException(command.replayId(), command.expectedStatus()));
            }
            if (command.nextStatus() == RecordingStatus.FAILED) {
                failureTransitions.add(command);
            }
            ReplayMetrics metrics = command.metrics().orElse(new ReplayMetrics(0L, 0L, 0L, 0L, 0L));
            ReplayRow updated = new ReplayRow(
                    current.replayId(), current.title(), current.description(), command.nextStatus(),
                    command.completionReason(), command.failure().map(FailureDetails::code),
                    command.failure().map(FailureDetails::description), current.adapterId(),
                    current.protocolVersion(), current.formatRevision(), current.storageBackend(),
                    current.storageKey(), metrics.durationNanos(), metrics.totalBytes(),
                    metrics.packetCount(), metrics.segmentCount(), metrics.checkpointCount(),
                    current.createdAt(), current.startedAt(), command.completedAt(), current.metadata(),
                    current.metadataRevision(), current.entityVersion() + 1L);
            rows.put(command.replayId(), updated);
            return CompletableFuture.completedFuture(updated);
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
