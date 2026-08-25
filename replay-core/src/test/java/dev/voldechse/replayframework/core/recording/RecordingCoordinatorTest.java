package dev.voldechse.replayframework.core.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
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
import dev.voldechse.replayframework.core.capture.CaptureRouter;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class RecordingCoordinatorTest {

    @Test
    void startActivatesOnlyAfterRecordingTransition() {
        TestRepository repository = new TestRepository();
        CompletableFuture<dev.voldechse.replayframework.core.port.ReplayRepository.ReplayRow>
                pendingTransition = new CompletableFuture<>();
        repository.transitionResult = pendingTransition;
        AtomicReference<RecordingCoordinator> coordinatorRef = new AtomicReference<>();
        PacketRegistry registry = registry();
        CaptureRouter router = new CaptureRouter(
                registry,
                failure -> coordinatorRef.get().onAdapterFailure(failure),
                (id, failure) -> coordinatorRef.get().onSinkFailure(id, failure));
        RecordingCoordinator coordinator = coordinator(
                router,
                registry,
                repository,
                (scope, participants) -> {
                    repository.events.add("scope");
                    return CompletableFuture.completedFuture(resolved(request(16L)));
                },
                (session, reason) -> CompletableFuture.completedFuture(session));
        coordinatorRef.set(coordinator);

        CompletionStage<? extends RecordingSession> start = coordinator.start(request(16L));
        assertFalse(start.toCompletableFuture().isDone());
        router.accept(packet());

        pendingTransition.complete(null);

        RecordingSession session = start.toCompletableFuture().join();
        assertEquals(RecordingStatus.RECORDING, session.status());
        assertEquals(0L, session.metrics().packetCount());
        router.accept(packet());
        assertEquals(1L, session.metrics().packetCount());
        assertEquals(List.of("create", "scope", "checkpoint", "transition"), repository.events);
    }

    @Test
    void initialCheckpointFailureFailsCatalogAndLeavesRouterEmpty() {
        TestRepository repository = new TestRepository();
        PacketRegistry registry = registry();
        AtomicReference<RecordingCoordinator> coordinatorRef = new AtomicReference<>();
        CaptureRouter router = new CaptureRouter(
                registry,
                failure -> coordinatorRef.get().onAdapterFailure(failure),
                (id, failure) -> coordinatorRef.get().onSinkFailure(id, failure));
        RecordingCoordinator coordinator = coordinator(
                router,
                registry,
                repository,
                (scope, participants) -> CompletableFuture.completedFuture(resolved(request(16L))),
                (session, reason) -> CompletableFuture.completedFuture(session),
                request -> CompletableFuture.failedFuture(new IllegalStateException("checkpoint")));
        coordinatorRef.set(coordinator);

        assertThrows(RuntimeException.class,
                () -> coordinator.start(request(16L)).toCompletableFuture().join());
        assertEquals(ReplayFailureCode.ADAPTER_ERROR, repository.failureCode.get());

        router.accept(packet());
        assertTrue(coordinator.active(RecordingSessionId.random()).isEmpty());
    }

    @Test
    void queueOverflowFailsOnlyTheAffectedOverlappingSession() {
        TestRepository repository = new TestRepository();
        PacketRegistry registry = registry();
        AtomicReference<RecordingCoordinator> coordinatorRef = new AtomicReference<>();
        CaptureRouter router = new CaptureRouter(
                registry,
                failure -> coordinatorRef.get().onAdapterFailure(failure),
                (id, failure) -> coordinatorRef.get().onSinkFailure(id, failure));
        RecordingCoordinator coordinator = coordinator(
                router,
                registry,
                repository,
                (scope, participants) -> CompletableFuture.completedFuture(resolved(request(16L))),
                (session, reason) -> CompletableFuture.completedFuture(session));
        coordinatorRef.set(coordinator);

        RecordingSession small = coordinator.start(request(1L)).toCompletableFuture().join();
        RecordingSession large = coordinator.start(request(16L)).toCompletableFuture().join();

        router.accept(packet(new byte[] {1, 2}));

        assertEquals(RecordingStatus.FAILED, small.status());
        assertEquals(ReplayFailureCode.QUEUE_OVERFLOW, small.failureCode().orElseThrow());
        assertEquals(RecordingStatus.RECORDING, large.status());
        assertEquals(1L, large.metrics().packetCount());
    }

    @Test
    void repeatedStopSharesOneFinalizationStage() {
        TestRepository repository = new TestRepository();
        PacketRegistry registry = registry();
        AtomicReference<RecordingCoordinator> coordinatorRef = new AtomicReference<>();
        AtomicInteger finalizations = new AtomicInteger();
        CaptureRouter router = new CaptureRouter(
                registry,
                failure -> coordinatorRef.get().onAdapterFailure(failure),
                (id, failure) -> coordinatorRef.get().onSinkFailure(id, failure));
        RecordingCoordinator coordinator = coordinator(
                router,
                registry,
                repository,
                (scope, participants) -> CompletableFuture.completedFuture(resolved(request(16L))),
                (session, reason) -> {
                    finalizations.incrementAndGet();
                    return CompletableFuture.completedFuture(session);
                });
        coordinatorRef.set(coordinator);

        RecordingSession session = coordinator.start(request(16L)).toCompletableFuture().join();
        CompletionStage<RecordingSession> first = session.stop();
        CompletionStage<RecordingSession> second = session.stop();

        assertSame(first, second);
        assertEquals(1, finalizations.get());
        assertEquals(RecordingStatus.FINALIZING, session.status());
    }

    @Test
    void captureContextControlsChatPolicyWithoutPayloadHeuristics() {
        CapturePolicy policy = CapturePolicy.builder()
                .includeChat(false)
                .build();
        ResolvedRecordingScope scope = new ResolvedRecordingScope(
                RecordingScope.builder().build(),
                1L,
                Set.of(),
                List.of(),
                policy,
                Set.of(),
                packet -> true);
        CapturedPacket chat = CapturedPacket.from(new CaptureBridge.CapturePacket(
                UUID.randomUUID(),
                10L,
                1L,
                0,
                PacketPhase.PLAY,
                1,
                new byte[] {1},
                new CaptureContext(
                        Optional.of(PacketDisposition.CONFIGURABLE),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("hello"),
                        Optional.empty())));

        assertFalse(scope.accepts(chat));
    }

    private static RecordingCoordinator coordinator(
            CaptureRouter router,
            PacketRegistry registry,
            TestRepository repository,
            RecordingCoordinator.ScopeResolver scopeResolver,
            RecordingCoordinator.FinalizationHandler finalizationHandler) {
        return coordinator(router, registry, repository, scopeResolver, finalizationHandler,
                request -> {
                    repository.events.add("checkpoint");
                    return CompletableFuture.completedFuture(new ReplayCheckpoint(0, 0L, List.of()));
                });
    }

    private static RecordingCoordinator coordinator(
            CaptureRouter router,
            PacketRegistry registry,
            TestRepository repository,
            RecordingCoordinator.ScopeResolver scopeResolver,
            RecordingCoordinator.FinalizationHandler finalizationHandler,
            CheckpointEncoder checkpointEncoder) {
        return new RecordingCoordinator(
                adapter(registry, checkpointEncoder),
                router,
                repository,
                scopeResolver,
                new RecordingCoordinator.RecordingTarget(
                        ReplayStorageBackend.LOCAL,
                        ReplayId::toString),
                finalizationHandler,
                Runnable::run);
    }

    private static RecordingRequest request(long queueBytes) {
        return RecordingRequest.builder()
                .title("test")
                .description("description")
                .scope(RecordingScope.builder().build())
                .capturePolicy(CapturePolicy.builder().build())
                .budget(dev.voldechse.replayframework.api.recording.ReplayBudget.builder()
                        .maxSegmentBytes(64L)
                        .maxQueueBytes(queueBytes)
                        .build())
                .build();
    }

    private static ResolvedRecordingScope resolved(RecordingRequest request) {
        return new ResolvedRecordingScope(
                request.scope(),
                1L,
                Set.of(),
                List.of(),
                request.capturePolicy(),
                request.participants(),
                packet -> true);
    }

    private static CaptureBridge.CapturePacket packet() {
        return packet(new byte[] {1});
    }

    private static CaptureBridge.CapturePacket packet(byte[] payload) {
        return new CaptureBridge.CapturePacket(
                UUID.randomUUID(),
                10L,
                1L,
                0,
                PacketPhase.PLAY,
                1,
                payload,
                new CaptureContext(
                        Optional.of(PacketDisposition.STATEFUL),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    private static PacketRegistry registry() {
        return PacketRegistry.of(List.of(new PacketDescriptor(
                "play.clientbound.test_state",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                1,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "test/state")));
    }

    private static ReplayAdapter adapter(PacketRegistry registry, CheckpointEncoder checkpointEncoder) {
        AdapterDescriptor descriptor = new AdapterDescriptor(
                "paper-26.2-test", 0, 0, registry.fingerprint());
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
                return checkpointEncoder;
            }

            @Override
            public PlaybackBridge openPlayback(Player player) {
                return null;
            }
        };
    }

    private static final class TestRepository implements dev.voldechse.replayframework.core.port.ReplayRepository {
        private final List<String> events = new ArrayList<>();
        private ReplayId replayId;
        private CompletableFuture<dev.voldechse.replayframework.core.port.ReplayRepository.ReplayRow>
                transitionResult = CompletableFuture.completedFuture(null);
        private final AtomicReference<ReplayFailureCode> failureCode = new AtomicReference<>();

        @Override
        public CompletionStage<ReplayRow> create(ReplayCreate command) {
            replayId = command.replayId();
            events.add("create");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Optional<ReplayRow>> find(ReplayId replayId) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<ReplayPage<ReplayRow>> page(ReplayQuery query) {
            return CompletableFuture.completedFuture(new ReplayPage<>(List.of(), Optional.empty()));
        }

        @Override
        public CompletionStage<ReplayRow> transition(ReplayTransition command) {
            if (command.nextStatus() == RecordingStatus.FAILED) {
                failureCode.set(command.failure().orElseThrow().code());
            }
            events.add(command.nextStatus() == RecordingStatus.RECORDING
                    ? "transition"
                    : "failure");
            return transitionResult;
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
