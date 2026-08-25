package dev.voldechse.replayframework.core.playback;

import com.google.gson.Gson;
import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.playback.PlaybackRequest;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.core.artifact.ArtifactIntegrityVerifier;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.ReplayManifestCodec;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ByteRange;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PlaybackSessionIsolationTest {

    @Test
    void oneViewerCannotOwnTwoActivePlaybackSessions() {
        PlaybackCoordinator coordinator = new PlaybackCoordinator();
        UUID viewerId = UUID.randomUUID();
        PlaybackSession first = session(viewerId);
        PlaybackSession second = session(viewerId);

        coordinator.reserve(first.id(), viewerId);
        coordinator.register(first);

        assertSame(first, coordinator.active(first.id()).orElseThrow());
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.reserve(second.id(), viewerId));

        coordinator.release(first.id(), viewerId);
        coordinator.reserve(second.id(), viewerId);
        coordinator.register(second);

        assertSame(second, coordinator.active(second.id()).orElseThrow());
    }

    @Test
    void serviceReservesViewerBeforeRepositoryAndArtifactAccess(@TempDir Path temporaryDirectory) {
        BlockingRepository repository = new BlockingRepository();
        CountingStorage storage = new CountingStorage();
        DefaultPlaybackService service = service(repository, storage, temporaryDirectory);
        UUID viewerId = UUID.randomUUID();
        ReplayId replayId = ReplayId.random();
        PlaybackRequest request = PlaybackRequest.builder()
                .replay(replayId)
                .viewer(viewer(viewerId))
                .build();

        CompletionStage<PlaybackSession> firstOpen = service.open(request);
        assertEquals(1, repository.findCalls.get());

        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.open(request).toCompletableFuture().join());
        assertEquals(1, repository.findCalls.get());
        assertEquals(0, storage.existsCalls.get());

        repository.result.complete(Optional.empty());
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> firstOpen.toCompletableFuture().join());

        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.open(request).toCompletableFuture().join());
        assertEquals(2, repository.findCalls.get());
    }

    @Test
    void eventListenerFailureDoesNotStopOtherListenersOrTerminalCompletion() {
        PlaybackSessionId sessionId = PlaybackSessionId.random();
        ReplayId replayId = ReplayId.random();
        UUID viewerId = UUID.randomUUID();
        PlaybackEventDispatcher dispatcher = new PlaybackEventDispatcher(
                Runnable::run,
                ignored -> {
                });
        AtomicInteger received = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        ReplayEventPublisher.Subscription failing = dispatcher.subscribe(event -> {
            throw new IllegalStateException("listener failure");
        });
        ReplayEventPublisher.Subscription healthy = dispatcher.subscribe(event -> {
            received.incrementAndGet();
            if (event instanceof ReplayEventPublisher.PlaybackCompleted) {
                completed.incrementAndGet();
            }
        });

        PlaybackSnapshot buffering = snapshot(PlaybackStatus.BUFFERING);
        dispatcher.statusChanged(
                sessionId,
                replayId,
                viewerId,
                PlaybackStatus.PREPARING,
                PlaybackStatus.BUFFERING,
                buffering);
        dispatcher.completed(
                sessionId,
                replayId,
                viewerId,
                snapshot(PlaybackStatus.CLOSED),
                Optional.empty());

        assertEquals(2, received.get());
        assertEquals(1, completed.get());

        failing.close();
        healthy.close();
        dispatcher.completed(
                sessionId,
                replayId,
                viewerId,
                snapshot(PlaybackStatus.CLOSED),
                Optional.empty());
        assertEquals(2, received.get());
    }

    @Test
    void timelineObserverReceivesStatusAndTerminalTransitions() {
        List<PlaybackStatus> statuses = new ArrayList<>();
        List<PlaybackStatus> completions = new ArrayList<>();
        PlaybackTimeline.Listener listener = new PlaybackTimeline.Listener() {
            @Override
            public void onStatusChanged(
                    PlaybackStatus previous,
                    PlaybackStatus current,
                    PlaybackSnapshot snapshot) {
                statuses.add(current);
            }

            @Override
            public void onCompleted(PlaybackSnapshot snapshot, Throwable failure) {
                completions.add(snapshot.status());
            }
        };
        PlaybackTimeline.PlaybackData data = new PlaybackTimeline.PlaybackData() {
            @Override
            public CompletionStage<PlaybackTimeline.BufferProgress> ensureAvailable(
                    Duration position,
                    PlaybackSpeed speed,
                    PrefetchPlanner.Direction direction) {
                return CompletableFuture.completedFuture(new PlaybackTimeline.BufferProgress(
                        Duration.ZERO,
                        Duration.ofSeconds(20L)));
            }

            @Override
            public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletionStage<List<RawPacketFrame>> framesBetween(
                    Duration startInclusive,
                    Duration endInclusive) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public PlaybackTimeline.BufferProgress progress(Duration position) {
                return new PlaybackTimeline.BufferProgress(
                        Duration.ZERO,
                        Duration.ofSeconds(20L));
            }

            @Override
            public void close() {
            }
        };
        PlaybackBridge bridge = new PlaybackBridge() {
            @Override
            public void send(RawPacketFrame frame) {
            }

            @Override
            public void resetView() {
            }

            @Override
            public void close() {
            }
        };
        PlaybackTimeline timeline = new PlaybackTimeline(
                Duration.ofSeconds(20L),
                ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L))),
                data,
                bridge,
                PlaybackBufferOptions.builder().build(),
                Runnable::run,
                System::nanoTime,
                listener);

        timeline.prepare().toCompletableFuture().join();
        timeline.closeAsync().toCompletableFuture().join();

        assertEquals(List.of(PlaybackStatus.BUFFERING, PlaybackStatus.PAUSED,
                PlaybackStatus.CLOSED), statuses);
        assertEquals(List.of(PlaybackStatus.CLOSED), completions);
    }

    @Test
    void endedTimelineCanRestartAndReconstructItsInitialView() {
        PlaybackTimeline timeline = timeline(new TrackingPlaybackData(), new TrackingBridge());
        timeline.prepare().toCompletableFuture().join();

        assertEquals(
                PlaybackStatus.ENDED,
                timeline.seekTo(Duration.ofSeconds(20L)).toCompletableFuture().join().status());
        assertEquals(
                PlaybackStatus.PAUSED,
                timeline.restart().toCompletableFuture().join().status());

        timeline.closeAsync().toCompletableFuture().join();
    }

    @Test
    void sessionsKeepTimelineStateAndResourcesIndependent() {
        PlaybackCoordinator coordinator = new PlaybackCoordinator();
        TrackingPlaybackData firstData = new TrackingPlaybackData();
        TrackingPlaybackData secondData = new TrackingPlaybackData();
        TrackingBridge firstBridge = new TrackingBridge();
        TrackingBridge secondBridge = new TrackingBridge();
        PlaybackTimeline firstTimeline = timeline(firstData, firstBridge);
        PlaybackTimeline secondTimeline = timeline(secondData, secondBridge);
        firstTimeline.prepare().toCompletableFuture().join();
        secondTimeline.prepare().toCompletableFuture().join();

        DefaultPlaybackSession first = new DefaultPlaybackSession(
                PlaybackSessionId.random(),
                ReplayId.random(),
                UUID.randomUUID(),
                firstTimeline,
                coordinator);
        DefaultPlaybackSession second = new DefaultPlaybackSession(
                PlaybackSessionId.random(),
                first.replayId(),
                UUID.randomUUID(),
                secondTimeline,
                coordinator);
        coordinator.reserve(first.id(), first.viewerId());
        coordinator.reserve(second.id(), second.viewerId());
        coordinator.register(first);
        coordinator.register(second);

        first.seekTo(Duration.ofSeconds(5L)).toCompletableFuture().join();
        first.speed(PlaybackSpeed.DOUBLE);

        assertEquals(Duration.ofSeconds(5L), first.snapshot().position());
        assertEquals(Duration.ZERO, second.snapshot().position());
        assertEquals(PlaybackSpeed.DOUBLE, first.snapshot().speed());
        assertEquals(PlaybackSpeed.NORMAL, second.snapshot().speed());

        first.close().toCompletableFuture().join();
        assertEquals(1, firstData.closeCount.get());
        assertEquals(1, firstBridge.closeCount.get());
        assertEquals(0, secondData.closeCount.get());
        assertEquals(0, secondBridge.closeCount.get());
        assertEquals(java.util.Optional.empty(), coordinator.active(first.id()));
        assertSame(second, coordinator.active(second.id()).orElseThrow());

        second.close().toCompletableFuture().join();
        assertEquals(1, secondData.closeCount.get());
        assertEquals(1, secondBridge.closeCount.get());
    }

    private static PlaybackTimeline timeline(
            TrackingPlaybackData data,
            TrackingBridge bridge) {
        return new PlaybackTimeline(
                Duration.ofSeconds(20L),
                ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L))),
                data,
                bridge,
                PlaybackBufferOptions.builder().build(),
                Runnable::run,
                System::nanoTime);
    }

    private static PlaybackSnapshot snapshot(PlaybackStatus status) {
        return new PlaybackSnapshot(
                Duration.ZERO,
                Duration.ofSeconds(20L),
                PlaybackSpeed.NORMAL,
                status,
                Duration.ZERO,
                Duration.ZERO);
    }

    private static PlaybackSession session(UUID viewerId) {
        PlaybackSessionId sessionId = PlaybackSessionId.random();
        ReplayId replayId = ReplayId.random();
        PlaybackSnapshot snapshot = new PlaybackSnapshot(
                Duration.ZERO,
                Duration.ofSeconds(20L),
                PlaybackSpeed.NORMAL,
                PlaybackStatus.PAUSED,
                Duration.ZERO,
                Duration.ZERO);
        return new PlaybackSession() {
            @Override
            public PlaybackSessionId id() {
                return sessionId;
            }

            @Override
            public ReplayId replayId() {
                return replayId;
            }

            @Override
            public UUID viewerId() {
                return viewerId;
            }

            @Override
            public PlaybackSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public void play() {
            }

            @Override
            public void pause() {
            }

            @Override
            public void speed(PlaybackSpeed speed) {
            }

            @Override
            public CompletionStage<PlaybackSnapshot> restart() {
                return CompletableFuture.completedFuture(snapshot);
            }

            @Override
            public CompletionStage<PlaybackSnapshot> seekTo(Duration position) {
                return CompletableFuture.completedFuture(snapshot);
            }

            @Override
            public CompletionStage<PlaybackSnapshot> seekBy(Duration delta) {
                return CompletableFuture.completedFuture(snapshot);
            }

            @Override
            public CompletionStage<PlaybackSnapshot> close() {
                return CompletableFuture.completedFuture(snapshot);
            }
        };
    }

    private static DefaultPlaybackService service(
            ReplayRepository repository,
            ReplayStorage storage,
            Path temporaryDirectory) {
        PacketRegistry registry = PacketRegistry.of(List.of());
        ReplayAdapter adapter = new ReplayAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("paper-26.2", 0, 1, registry.fingerprint());
            }

            @Override
            public PacketRegistry packetRegistry() {
                return registry;
            }

            @Override
            public CaptureBridge captureBridge() {
                return null;
            }

            @Override
            public CheckpointEncoder checkpointEncoder() {
                return null;
            }

            @Override
            public PlaybackBridge openPlayback(org.bukkit.entity.Player player) {
                throw new AssertionError("bridge must not be opened");
            }
        };
        ReplayArtifactReader artifactReader = new ReplayArtifactReader(
                storage,
                new ReplayManifestCodec(new Gson()),
                new ArtifactIntegrityVerifier(),
                Runnable::run,
                temporaryDirectory.resolve("artifact-work"));
        SegmentCache segmentCache = (replay, segment) ->
                CompletableFuture.failedFuture(new AssertionError("segment cache must not be used"));
        PlaybackEventDispatcher dispatcher = new PlaybackEventDispatcher(
                Runnable::run,
                ignored -> {
                });
        return new DefaultPlaybackService(
                repository,
                artifactReader,
                adapter,
                segmentCache,
                new dev.voldechse.replayframework.format.ReplayIndexReader(),
                new dev.voldechse.replayframework.format.ReplayCheckpointReader(),
                new dev.voldechse.replayframework.format.ReplaySegmentReader(),
                Runnable::run,
                Runnable::run,
                new PlaybackCoordinator(),
                dispatcher);
    }

    private static org.bukkit.entity.Player viewer(UUID viewerId) {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return viewerId;
                    }
                    if (method.getName().equals("toString")) {
                        return "test-player";
                    }
                    return null;
                });
    }

    private static final class BlockingRepository implements ReplayRepository {
        private final AtomicInteger findCalls = new AtomicInteger();
        private final CompletableFuture<Optional<ReplayRow>> result = new CompletableFuture<>();

        @Override
        public CompletionStage<ReplayRow> create(ReplayCreate command) {
            return unsupported();
        }

        @Override
        public CompletionStage<Optional<ReplayRow>> find(ReplayId replayId) {
            findCalls.incrementAndGet();
            return result;
        }

        @Override
        public CompletionStage<ReplayPage<ReplayRow>> page(ReplayQuery query) {
            return unsupported();
        }

        @Override
        public CompletionStage<ReplayRow> transition(ReplayTransition command) {
            return unsupported();
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            return unsupported();
        }

        private static <T> CompletionStage<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    private static final class CountingStorage implements ReplayStorage {
        private final AtomicInteger existsCalls = new AtomicInteger();

        @Override
        public CompletionStage<StagingReplay> stage(ReplayId replayId) {
            return unsupported();
        }

        @Override
        public CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source) {
            return unsupported();
        }

        @Override
        public CompletionStage<Void> publish(StagingReplay staging) {
            return unsupported();
        }

        @Override
        public CompletionStage<Path> fetch(
                ReplayId replayId,
                ArtifactKey key,
                Optional<ByteRange> range,
                Path target) {
            return unsupported();
        }

        @Override
        public CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key) {
            existsCalls.incrementAndGet();
            return unsupported();
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            return unsupported();
        }

        private static <T> CompletionStage<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }

    private static final class TrackingPlaybackData implements PlaybackTimeline.PlaybackData {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public CompletionStage<PlaybackTimeline.BufferProgress> ensureAvailable(
                Duration position,
                PlaybackSpeed speed,
                PrefetchPlanner.Direction direction) {
            return CompletableFuture.completedFuture(new PlaybackTimeline.BufferProgress(
                    Duration.ZERO,
                    Duration.ofSeconds(20L)));
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesBetween(
                Duration startInclusive,
                Duration endInclusive) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public PlaybackTimeline.BufferProgress progress(Duration position) {
            return new PlaybackTimeline.BufferProgress(
                    Duration.ZERO,
                    Duration.ofSeconds(20L));
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class TrackingBridge implements PlaybackBridge {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void send(RawPacketFrame frame) {
        }

        @Override
        public void resetView() {
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
