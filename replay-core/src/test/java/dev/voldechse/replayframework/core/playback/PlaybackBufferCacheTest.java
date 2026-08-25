package dev.voldechse.replayframework.core.playback;

import com.google.gson.Gson;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.core.artifact.ArtifactIntegrityVerifier;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.playback.buffer.PlaybackBuffer;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.core.playback.cache.CacheEntryLease;
import dev.voldechse.replayframework.core.playback.cache.DiskSegmentCache;
import dev.voldechse.replayframework.core.playback.cache.MemoryPacketBuffer;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.ReplayManifest.ArtifactFile;
import dev.voldechse.replayframework.format.ReplayManifest.ArtifactType;
import dev.voldechse.replayframework.format.ReplaySegmentWriter;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ByteRange;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackBufferCacheTest {

    private static final ReplayId REPLAY_ID = ReplayId.parse(
            "22222222-2222-2222-2222-222222222222");
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void concurrentSameDigestRequestsShareOneFetch(@TempDir Path temporaryDirectory)
            throws IOException {
        byte[] segmentBytes = "shared-segment".getBytes();
        ArtifactFile artifact = segmentArtifact(segmentBytes, 0);
        ReplayArtifactReader.VerifiedReplay replay = verifiedReplay(artifact);
        CountingStorage storage = new CountingStorage();
        storage.artifacts.put(artifact.path(), segmentBytes);
        storage.fetchGate = new CompletableFuture<>();
        ReplayArtifactReader reader = reader(storage, temporaryDirectory, DIRECT_EXECUTOR);
        DiskSegmentCache cache = new DiskSegmentCache(
                temporaryDirectory.resolve("cache"),
                1024L,
                reader,
                DIRECT_EXECUTOR);
        SegmentCache.SegmentRef segment = new SegmentCache.SegmentRef(
                0,
                artifact,
                0L,
                1L);

        CompletionStage<CacheEntryLease> first = cache.ensureAvailable(replay, segment);
        CompletionStage<CacheEntryLease> second = cache.ensureAvailable(replay, segment);

        assertEquals(1, storage.fetchCount.get());
        storage.fetchGate.complete(null);
        CacheEntryLease firstLease = first.toCompletableFuture().join();
        CacheEntryLease secondLease = second.toCompletableFuture().join();
        assertEquals(firstLease.path(), secondLease.path());
        firstLease.close();
        secondLease.close();
        cache.close();
    }

    @Test
    void corruptHitIsRefetched(@TempDir Path temporaryDirectory) throws IOException {
        byte[] segmentBytes = "valid-segment".getBytes();
        ArtifactFile artifact = segmentArtifact(segmentBytes, 0);
        ReplayArtifactReader.VerifiedReplay replay = verifiedReplay(artifact);
        CountingStorage storage = new CountingStorage();
        storage.artifacts.put(artifact.path(), segmentBytes);
        ReplayArtifactReader reader = reader(storage, temporaryDirectory, DIRECT_EXECUTOR);
        Path segmentsRoot = temporaryDirectory.resolve("cache").resolve("segments");
        Files.createDirectories(segmentsRoot);
        Files.writeString(segmentsRoot.resolve(artifact.sha256() + ".segment"), "corrupt");
        DiskSegmentCache cache = new DiskSegmentCache(
                temporaryDirectory.resolve("cache"),
                1024L,
                reader,
                DIRECT_EXECUTOR);

        CacheEntryLease lease = cache.ensureAvailable(
                        replay,
                        new SegmentCache.SegmentRef(0, artifact, 0L, 1L))
                .toCompletableFuture()
                .join();

        assertEquals(1, storage.fetchCount.get());
        assertArrayEquals(segmentBytes, Files.readAllBytes(lease.path()));
        lease.close();
        cache.close();
    }

    @Test
    void leasedEntryIsNotEvicted(@TempDir Path temporaryDirectory) throws IOException {
        byte[] firstBytes = "aaaa".getBytes();
        byte[] secondBytes = "bbbb".getBytes();
        ArtifactFile firstArtifact = segmentArtifact(firstBytes, 0);
        ArtifactFile secondArtifact = segmentArtifact(secondBytes, 1);
        ReplayArtifactReader.VerifiedReplay firstReplay = verifiedReplay(firstArtifact);
        ReplayArtifactReader.VerifiedReplay secondReplay = verifiedReplay(secondArtifact);
        CountingStorage storage = new CountingStorage();
        storage.artifacts.put(firstArtifact.path(), firstBytes);
        storage.artifacts.put(secondArtifact.path(), secondBytes);
        ReplayArtifactReader reader = reader(storage, temporaryDirectory, DIRECT_EXECUTOR);
        DiskSegmentCache cache = new DiskSegmentCache(
                temporaryDirectory.resolve("cache"),
                firstBytes.length,
                reader,
                DIRECT_EXECUTOR);

        CacheEntryLease firstLease = cache.ensureAvailable(
                        firstReplay,
                        new SegmentCache.SegmentRef(0, firstArtifact, 0L, 1L))
                .toCompletableFuture()
                .join();
        CacheEntryLease secondLease = cache.ensureAvailable(
                        secondReplay,
                        new SegmentCache.SegmentRef(1, secondArtifact, 1L, 2L))
                .toCompletableFuture()
                .join();

        assertTrue(Files.exists(firstLease.path()));
        assertFalse(secondLease.cached());
        firstLease.close();
        secondLease.close();
        cache.close();
    }

    @Test
    void oversizedSegmentUsesEphemeralLease(@TempDir Path temporaryDirectory)
            throws IOException {
        byte[] segmentBytes = "larger-than-cache".getBytes();
        ArtifactFile artifact = segmentArtifact(segmentBytes, 0);
        ReplayArtifactReader.VerifiedReplay replay = verifiedReplay(artifact);
        CountingStorage storage = new CountingStorage();
        storage.artifacts.put(artifact.path(), segmentBytes);
        ReplayArtifactReader reader = reader(storage, temporaryDirectory, DIRECT_EXECUTOR);
        DiskSegmentCache cache = new DiskSegmentCache(
                temporaryDirectory.resolve("cache"),
                segmentBytes.length - 1L,
                reader,
                DIRECT_EXECUTOR);

        CacheEntryLease lease = cache.ensureAvailable(
                        replay,
                        new SegmentCache.SegmentRef(0, artifact, 0L, 1L))
                .toCompletableFuture()
                .join();
        Path ephemeralPath = lease.path();

        assertFalse(lease.cached());
        assertTrue(Files.exists(ephemeralPath));
        lease.close();
        assertFalse(Files.exists(ephemeralPath));
        cache.close();
    }

    @Test
    void memoryBufferHonorsWindowAndBudget() {
        RawPacketFrame first = frame(0L, 1);
        RawPacketFrame middle = frame(1L, 2);
        RawPacketFrame last = frame(2L, 3);
        MemoryPacketBuffer buffer = new MemoryPacketBuffer(2L * (64L + 1L));
        buffer.retain(Duration.ofNanos(1L), Duration.ZERO, Duration.ofNanos(1L));

        buffer.addAll(List.of(first, middle, last));

        assertEquals(List.of(middle, last), buffer.framesBetween(
                Duration.ZERO,
                Duration.ofNanos(3L)));
        assertEquals(2L * (64L + 1L), buffer.accountedBytes());
        byte[] originalPayload = middle.payload();
        originalPayload[0] = 99;
        assertEquals(2, middle.payload()[0]);
        assertThrows(
                MemoryPacketBuffer.MemoryBufferCapacityException.class,
                () -> buffer.addAll(List.of(new RawPacketFrame(
                        3L,
                        1L,
                        4,
                        PacketPhase.PLAY,
                        1,
                        new byte[100]))));
    }

    @Test
    void plannerScalesSpeedAndPrioritizesDirection() {
        List<SegmentCache.SegmentRef> segments = List.of(
                segmentRef(0, 0L, 1_000_000_000L, 10),
                segmentRef(1, 1_000_000_000L, 2_000_000_000L, 10),
                segmentRef(2, 2_000_000_000L, 3_000_000_000L, 10),
                segmentRef(3, 3_000_000_000L, 4_000_000_000L, 10));
        PlaybackBufferOptions options = PlaybackBufferOptions.builder()
                .preloadAhead(Duration.ofSeconds(1L))
                .retainBehind(Duration.ofSeconds(1L))
                .minimumResumeBuffer(Duration.ofMillis(500L))
                .diskBudgetBytes(100L)
                .build();
        PrefetchPlanner planner = new PrefetchPlanner();

        PrefetchPlanner.Plan normal = planner.plan(
                Duration.ofSeconds(1L),
                Duration.ofSeconds(4L),
                PlaybackSpeed.NORMAL,
                PrefetchPlanner.Direction.FORWARD,
                segments,
                options);
        PrefetchPlanner.Plan quadruple = planner.plan(
                Duration.ofSeconds(1L),
                Duration.ofSeconds(4L),
                PlaybackSpeed.QUADRUPLE,
                PrefetchPlanner.Direction.FORWARD,
                segments,
                options);
        PrefetchPlanner.Plan backward = planner.plan(
                Duration.ofSeconds(2L),
                Duration.ofSeconds(4L),
                PlaybackSpeed.NORMAL,
                PrefetchPlanner.Direction.BACKWARD,
                segments,
                options);

        assertTrue(quadruple.endNanos() > normal.endNanos());
        assertEquals(0, backward.segments().getFirst().ordinal());
        assertTrue(backward.minimumResumeSatisfied());
    }

    @Test
    void playbackBufferLoadsAndReportsProgress(@TempDir Path temporaryDirectory)
            throws IOException {
        List<RawPacketFrame> frames = List.of(frame(0L, 1), frame(1_000_000_000L, 2));
        byte[] segmentBytes = writeSegment(temporaryDirectory, frames);
        ArtifactFile artifact = segmentArtifact(segmentBytes, 0);
        ReplayArtifactReader.VerifiedReplay replay = verifiedReplay(artifact);
        CountingStorage storage = new CountingStorage();
        storage.artifacts.put(artifact.path(), segmentBytes);
        ReplayArtifactReader reader = reader(storage, temporaryDirectory, DIRECT_EXECUTOR);
        DiskSegmentCache cache = new DiskSegmentCache(
                temporaryDirectory.resolve("cache"),
                1024L * 1024L,
                reader,
                DIRECT_EXECUTOR);
        PlaybackBufferOptions options = PlaybackBufferOptions.builder()
                .preloadAhead(Duration.ofSeconds(2L))
                .minimumResumeBuffer(Duration.ofMillis(1L))
                .memoryBudgetBytes(1024L * 1024L)
                .diskBudgetBytes(1024L * 1024L)
                .build();
        PlaybackBuffer buffer = new PlaybackBuffer(
                replay,
                List.of(new SegmentCache.SegmentRef(
                        0,
                        artifact,
                        0L,
                        1_000_000_000L)),
                reader,
                cache,
                new ReplaySegmentReader(),
                options,
                DIRECT_EXECUTOR);

        PlaybackBuffer.BufferProgress progress = buffer.ensureAvailable(
                        Duration.ZERO,
                        PlaybackSpeed.NORMAL,
                        PrefetchPlanner.Direction.FORWARD)
                .toCompletableFuture()
                .join();

        assertEquals(frames, buffer.framesBetween(
                        Duration.ZERO,
                        Duration.ofSeconds(2L))
                .toCompletableFuture()
                .join());
        assertTrue(progress.bufferedAhead().compareTo(Duration.ZERO) > 0);
        assertTrue(progress.memoryBytes() > 0L);
        assertTrue(progress.minimumResumeSatisfied());
        buffer.close();
        cache.close();
    }

    @Test
    void failedDecodeDoesNotPublishPartialFrames(@TempDir Path temporaryDirectory)
            throws IOException {
        byte[] invalidSegment = "not-a-segment".getBytes();
        ArtifactFile artifact = segmentArtifact(invalidSegment, 0);
        ReplayArtifactReader.VerifiedReplay replay = verifiedReplay(artifact);
        CountingStorage storage = new CountingStorage();
        storage.artifacts.put(artifact.path(), invalidSegment);
        ReplayArtifactReader reader = reader(storage, temporaryDirectory, DIRECT_EXECUTOR);
        DiskSegmentCache cache = new DiskSegmentCache(
                temporaryDirectory.resolve("cache"),
                1024L,
                reader,
                DIRECT_EXECUTOR);
        PlaybackBuffer buffer = new PlaybackBuffer(
                replay,
                List.of(new SegmentCache.SegmentRef(0, artifact, 0L, 1L)),
                reader,
                cache,
                new ReplaySegmentReader(),
                PlaybackBufferOptions.builder().build(),
                DIRECT_EXECUTOR);

        Throwable failure = assertThrows(
                Exception.class,
                () -> buffer.framesBetween(Duration.ZERO, Duration.ofSeconds(1L))
                        .toCompletableFuture()
                        .join());

        assertNotEquals(null, failure);
        PlaybackBuffer.BufferProgress progress = buffer.progress(Duration.ZERO);
        assertEquals(0L, progress.memoryBytes());
        assertEquals(Duration.ZERO, progress.bufferedAhead());
        buffer.close();
        cache.close();
    }

    private static RawPacketFrame frame(long elapsedNanos, int payloadValue) {
        return new RawPacketFrame(
                elapsedNanos,
                elapsedNanos,
                payloadValue,
                PacketPhase.PLAY,
                1,
                new byte[]{(byte) payloadValue});
    }

    private static SegmentCache.SegmentRef segmentRef(
            int ordinal,
            long startNanos,
            long endNanos,
            int size) {
        byte[] bytes = new byte[size];
        ArtifactFile artifact = segmentArtifact(bytes, ordinal);
        return new SegmentCache.SegmentRef(ordinal, artifact, startNanos, endNanos);
    }

    private static ArtifactFile segmentArtifact(byte[] bytes, int ordinal) {
        return new ArtifactFile(
                ArtifactType.SEGMENT,
                "segments/" + String.format("%08d", ordinal) + ".segment",
                bytes.length,
                sha256(bytes));
    }

    private static ReplayArtifactReader.VerifiedReplay verifiedReplay(ArtifactFile artifact) {
        ReplayManifest manifest = new ReplayManifest(
                REPLAY_ID.toString(),
                "paper-26.2",
                0,
                "fingerprint",
                ReplayManifest.CURRENT_FORMAT_REVISION,
                Duration.ofSeconds(4L).toNanos(),
                List.of(artifact));
        return new ReplayArtifactReader.VerifiedReplay(REPLAY_ID, manifest);
    }

    private static ReplayArtifactReader reader(
            CountingStorage storage,
            Path temporaryDirectory,
            Executor executor) {
        return new ReplayArtifactReader(
                storage,
                new dev.voldechse.replayframework.format.ReplayManifestCodec(new Gson()),
                new ArtifactIntegrityVerifier(),
                executor,
                temporaryDirectory.resolve("reader-work"));
    }

    private static byte[] writeSegment(Path temporaryDirectory, List<RawPacketFrame> frames)
            throws IOException {
        Path target = temporaryDirectory.resolve("source-" + UUID.randomUUID() + ".segment");
        try (ReplaySegmentWriter writer = new ReplaySegmentWriter(target, "paper-26.2")) {
            for (RawPacketFrame frame : frames) {
                writer.append(frame);
            }
            writer.finish();
        }
        return Files.readAllBytes(target);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class CountingStorage implements ReplayStorage {

        private final Map<String, byte[]> artifacts = new ConcurrentHashMap<>();
        private final AtomicInteger fetchCount = new AtomicInteger();
        private volatile CompletableFuture<Void> fetchGate;

        @Override
        public CompletionStage<StagingReplay> stage(ReplayId replayId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Void> publish(StagingReplay staging) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Path> fetch(
                ReplayId replayId,
                ArtifactKey key,
                Optional<ByteRange> range,
                Path target) {
            fetchCount.incrementAndGet();
            byte[] bytes = artifacts.get(key.value());
            if (bytes == null || range.isPresent()) {
                return CompletableFuture.failedFuture(new NoSuchFileException(key.value()));
            }
            CompletableFuture<Void> gate = fetchGate;
            CompletionStage<Void> ready = gate == null
                    ? CompletableFuture.completedFuture(null)
                    : gate;
            return ready.thenApply(ignored -> {
                try {
                    Files.createDirectories(target.toAbsolutePath().normalize().getParent());
                    Files.write(target, bytes);
                    return target;
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }

        @Override
        public CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key) {
            return CompletableFuture.completedFuture(artifacts.containsKey(key.value()));
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
