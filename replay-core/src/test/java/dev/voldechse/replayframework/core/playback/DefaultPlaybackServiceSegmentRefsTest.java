package dev.voldechse.replayframework.core.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.ReplayManifest;
import dev.voldechse.replayframework.format.SeekPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DefaultPlaybackServiceSegmentRefsTest {

    @Test
    void checkpointAnchorAtSealedBoundaryDoesNotExtendPreviousSegment() {
        ReplayManifest manifest = manifest(50L, 2);
        ReplayIndex index = ReplayIndex.of(List.of(
                point(0L, 0, 0, 0L),
                point(10L, 0, 0, 0L),
                point(20L, 0, 0, 10L),
                point(30L, 1, 0, 20L),
                point(40L, 1, 1, 0L),
                point(50L, 1, 1, 10L)));

        List<SegmentCache.SegmentRef> segments =
                DefaultPlaybackService.buildSegmentRefs(manifest, index);

        assertEquals(2, segments.size());
        assertEquals(10L, segments.get(0).startNanos());
        assertEquals(20L, segments.get(0).endNanos());
        assertEquals(40L, segments.get(1).startNanos());
        assertEquals(50L, segments.get(1).endNanos());
    }

    @Test
    void checkpointAnchorInsideSegmentDoesNotReplaceFrameBounds() {
        ReplayManifest manifest = manifest(25L, 1);
        ReplayIndex index = ReplayIndex.of(List.of(
                point(0L, 0, 0, 0L),
                point(10L, 0, 0, 0L),
                point(15L, 1, 0, 10L),
                point(20L, 1, 0, 10L),
                point(25L, 1, 0, 20L)));

        SegmentCache.SegmentRef segment =
                DefaultPlaybackService.buildSegmentRefs(manifest, index).getFirst();

        assertEquals(10L, segment.startNanos());
        assertEquals(25L, segment.endNanos());
    }

    @Test
    void finalSegmentRetainsPhysicalEndBeforeQuietReplayTail() {
        ReplayManifest manifest = manifest(100L, 1);
        ReplayIndex index = ReplayIndex.of(List.of(
                point(0L, 0, 0, 0L),
                point(10L, 0, 0, 0L),
                point(20L, 0, 0, 10L)));

        SegmentCache.SegmentRef segment =
                DefaultPlaybackService.buildSegmentRefs(manifest, index).getFirst();

        assertEquals(10L, segment.startNanos());
        assertEquals(20L, segment.endNanos());
    }

    private static SeekPoint point(
            long elapsedNanos,
            int checkpointOrdinal,
            int segmentOrdinal,
            long frameOffset) {
        return new SeekPoint(elapsedNanos, checkpointOrdinal, segmentOrdinal, frameOffset);
    }

    private static ReplayManifest manifest(long durationNanos, int segmentCount) {
        return new ReplayManifest(
                "11111111-1111-1111-1111-111111111111",
                "paper-test",
                1,
                "test-registry",
                ReplayManifest.CURRENT_FORMAT_REVISION,
                durationNanos,
                java.util.stream.IntStream.range(0, segmentCount)
                        .mapToObj(DefaultPlaybackServiceSegmentRefsTest::segmentArtifact)
                        .toList());
    }

    private static ReplayManifest.ArtifactFile segmentArtifact(int ordinal) {
        return new ReplayManifest.ArtifactFile(
                ReplayManifest.ArtifactType.SEGMENT,
                "segments/" + String.format("%08d", ordinal) + ".segment",
                1L,
                "0".repeat(64));
    }
}
