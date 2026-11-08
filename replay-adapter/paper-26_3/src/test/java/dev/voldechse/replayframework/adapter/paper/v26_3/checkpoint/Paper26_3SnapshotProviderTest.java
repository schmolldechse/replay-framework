package dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.ChunkLoadingPolicy;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Paper26_3SnapshotProviderTest {

    @Test
    void schedulesWorldAccessAndAppliesLoadedOnlyScopeIntersection() throws Exception {
        ExecutorService mainThread = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("paper-test-main-", 0).factory());
        try {
            RecordingWorldAccess access = new RecordingWorldAccess();
            Paper26_3SnapshotProvider provider = new Paper26_3SnapshotProvider(
                    scheduler(mainThread), access);
            RecordingScope scope = RecordingScope.builder()
                    .addWorld(net.kyori.adventure.key.Key.key("minecraft:world"))
                    .addRegion(new CuboidRegion(
                            net.kyori.adventure.key.Key.key("minecraft:world"),
                            new BlockPosition(0, 0, 0),
                            new BlockPosition(31, 255, 31)))
                    .build();

            CompletionStage<Paper26_3CheckpointEncoder.CheckpointSnapshot> stage = provider.snapshot(
                    new CheckpointEncoder.CheckpointRequest(
                            scope,
                            12L,
                            3L,
                            CheckpointEncoder.CheckpointKind.INITIAL));

            Paper26_3CheckpointEncoder.CheckpointSnapshot snapshot = stage.toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(List.of(new Paper26_3SnapshotProvider.ScopedChunk(
                            "minecraft:world", 0, 0),
                            new Paper26_3SnapshotProvider.ScopedChunk(
                                    "minecraft:world", 1, 1)),
                    access.snapshotChunks.get());
            assertEquals(List.of(), snapshot.packets());
            assertFalse(access.preloadCalled.get());
            assertNotEquals(Thread.currentThread(), access.accessThread.get());
        } finally {
            mainThread.shutdownNow();
        }
    }

    @Test
    void preloadsExactlyInclusiveRegionChunkBoundsBeforeSnapshot() {
        RecordingWorldAccess access = new RecordingWorldAccess();
        ExecutorService mainThread = Executors.newSingleThreadExecutor();
        try {
            Paper26_3SnapshotProvider provider = new Paper26_3SnapshotProvider(
                    scheduler(mainThread), access);
            RecordingScope scope = RecordingScope.builder()
                    .chunkLoadingPolicy(ChunkLoadingPolicy.PRELOAD_SCOPE)
                    .addRegion(new CuboidRegion(
                            net.kyori.adventure.key.Key.key("minecraft:world"),
                            new BlockPosition(-17, 0, 16),
                            new BlockPosition(16, 15, 33)))
                    .build();

            provider.snapshot(new CheckpointEncoder.CheckpointRequest(
                            scope,
                            0L,
                            0L,
                            CheckpointEncoder.CheckpointKind.PERIODIC))
                    .toCompletableFuture()
                    .join();

            assertTrue(access.preloadCalled.get());
            assertEquals(List.of(
                            new Paper26_3SnapshotProvider.ChunkCoordinate(-2, 1),
                            new Paper26_3SnapshotProvider.ChunkCoordinate(-2, 2),
                            new Paper26_3SnapshotProvider.ChunkCoordinate(-1, 1),
                            new Paper26_3SnapshotProvider.ChunkCoordinate(-1, 2),
                            new Paper26_3SnapshotProvider.ChunkCoordinate(0, 1),
                            new Paper26_3SnapshotProvider.ChunkCoordinate(0, 2),
                            new Paper26_3SnapshotProvider.ChunkCoordinate(1, 1),
                            new Paper26_3SnapshotProvider.ChunkCoordinate(1, 2)),
                    access.preloadedChunks.get());
        } finally {
            mainThread.shutdownNow();
        }
    }

    @Test
    void allocatesTheActualCapturePointOnlyAfterPreloadingCompletes() {
        RecordingWorldAccess access = new RecordingWorldAccess();
        CompletableFuture<Void> preloadGate = new CompletableFuture<>();
        access.preloadResult.set(preloadGate);
        AtomicBoolean pointAllocated = new AtomicBoolean();
        CheckpointEncoder.CapturePoint expected =
                new CheckpointEncoder.CapturePoint(42L, 7L, 3);
        Paper26_3SnapshotProvider provider = new Paper26_3SnapshotProvider(
                task -> CompletableFuture.completedFuture(task.get()),
                access,
                () -> {
                    pointAllocated.set(true);
                    return expected;
                });
        RecordingScope scope = RecordingScope.builder()
                .chunkLoadingPolicy(ChunkLoadingPolicy.PRELOAD_SCOPE)
                .addRegion(new CuboidRegion(
                        net.kyori.adventure.key.Key.key("minecraft:world"),
                        new BlockPosition(0, 0, 0),
                        new BlockPosition(15, 15, 15)))
                .build();

        CompletionStage<Paper26_3CheckpointEncoder.CheckpointSnapshot> stage = provider.snapshot(
                new CheckpointEncoder.CheckpointRequest(
                        scope, 10L, 2L, CheckpointEncoder.CheckpointKind.PERIODIC));
        assertFalse(pointAllocated.get());

        preloadGate.complete(null);
        Paper26_3CheckpointEncoder.CheckpointSnapshot snapshot =
                stage.toCompletableFuture().join();
        assertEquals(java.util.Optional.of(expected), snapshot.capturePoint());
    }

    @Test
    void propagatesSchedulerFailureAndRejectsCallsAfterClose() {
        RuntimeException failure = new RuntimeException("scheduler unavailable");
        Paper26_3SnapshotProvider provider = new Paper26_3SnapshotProvider(
                task -> CompletableFuture.failedFuture(failure),
                new RecordingWorldAccess());

        CompletionStage<Paper26_3CheckpointEncoder.CheckpointSnapshot> stage = provider.snapshot(
                new CheckpointEncoder.CheckpointRequest(
                        RecordingScope.builder().build(),
                        0L,
                        0L,
                        CheckpointEncoder.CheckpointKind.INITIAL));
        assertEquals(failure, assertThrows(java.util.concurrent.CompletionException.class,
                () -> stage.toCompletableFuture().join()).getCause());

        provider.close();
        assertTrue(provider.snapshot(new CheckpointEncoder.CheckpointRequest(
                        RecordingScope.builder().build(),
                        0L,
                        0L,
                        CheckpointEncoder.CheckpointKind.INITIAL))
                .toCompletableFuture()
                .isCompletedExceptionally());
    }

    private static Paper26_3SnapshotProvider.MainThreadScheduler scheduler(
            ExecutorService executor) {
        return task -> CompletableFuture.supplyAsync(task, executor);
    }

    private static final class RecordingWorldAccess
            implements Paper26_3SnapshotProvider.Paper26_3WorldAccess {
        private final AtomicBoolean preloadCalled = new AtomicBoolean();
        private final AtomicReference<Thread> accessThread = new AtomicReference<>();
        private final AtomicReference<List<Paper26_3SnapshotProvider.ScopedChunk>> snapshotChunks =
                new AtomicReference<>();
        private final AtomicReference<List<Paper26_3SnapshotProvider.ChunkCoordinate>> preloadedChunks =
                new AtomicReference<>();
        private final AtomicReference<CompletionStage<Void>> preloadResult =
                new AtomicReference<>(CompletableFuture.completedFuture(null));

        @Override
        public List<String> worldKeys() {
            accessThread.set(Thread.currentThread());
            return List.of("minecraft:world", "minecraft:nether");
        }

        @Override
        public List<Paper26_3SnapshotProvider.ChunkCoordinate> loadedChunks(String worldKey) {
            accessThread.set(Thread.currentThread());
            if (worldKey.equals("minecraft:world")) {
                return List.of(
                        new Paper26_3SnapshotProvider.ChunkCoordinate(0, 0),
                        new Paper26_3SnapshotProvider.ChunkCoordinate(1, 1),
                        new Paper26_3SnapshotProvider.ChunkCoordinate(2, 2));
            }
            return List.of(new Paper26_3SnapshotProvider.ChunkCoordinate(0, 0));
        }

        @Override
        public CompletionStage<Void> preload(
                String worldKey,
                List<Paper26_3SnapshotProvider.ChunkCoordinate> chunks) {
            accessThread.set(Thread.currentThread());
            preloadCalled.set(true);
            preloadedChunks.set(List.copyOf(chunks));
            return preloadResult.get();
        }

        @Override
        public Paper26_3CheckpointEncoder.CheckpointSnapshot snapshot(
                RecordingScope scope,
                List<Paper26_3SnapshotProvider.ScopedChunk> chunks) {
            accessThread.set(Thread.currentThread());
            snapshotChunks.set(new ArrayList<>(chunks));
            return new Paper26_3CheckpointEncoder.CheckpointSnapshot(List.of());
        }
    }
}
