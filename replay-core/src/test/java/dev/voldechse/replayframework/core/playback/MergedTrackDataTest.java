package dev.voldechse.replayframework.core.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class MergedTrackDataTest {

    @Test
    void answersACheckpointWithTheSameOrdinalOfEveryTrack() {
        FakeTrack global = new FakeTrack(List.of(frame(0L, 1)), List.of());
        FakeTrack world = new FakeTrack(List.of(frame(0L, 0), frame(0L, 2)), List.of());
        SeekPoint worldAnchor = new SeekPoint(5L, 1, 3, 40L);
        MergedTrackData data = new MergedTrackData(List.of(
                MergedTrackData.Track.of(global, ReplayIndex.of(List.of(
                        new SeekPoint(0L, 0, 0, 0L), new SeekPoint(5L, 1, 0, 0L)))),
                MergedTrackData.Track.of(world, ReplayIndex.of(List.of(
                        new SeekPoint(0L, 0, 0, 0L), worldAnchor, new SeekPoint(6L, 1, 3, 40L))))));

        List<RawPacketFrame> frames = data.checkpointFrames(new SeekPoint(5L, 1, 0, 0L))
                .toCompletableFuture().join();
        data.framesAfter(new SeekPoint(5L, 1, 0, 0L), Duration.ofNanos(10L)).toCompletableFuture().join();

        assertEquals(List.of(0, 1, 2), frames.stream().map(RawPacketFrame::sequence).toList());
        assertEquals(worldAnchor, world.requestedCheckpoint);
        assertEquals(worldAnchor, world.requestedAfter);
    }

    @Test
    void mergesFramesOfAllTracksInCaptureOrder() {
        FakeTrack global = new FakeTrack(List.of(), List.of(frame(20L, 1), frame(30L, 0)));
        FakeTrack world = new FakeTrack(List.of(), List.of(frame(10L, 0), frame(20L, 0), frame(20L, 2)));
        MergedTrackData data = data(global, world);

        List<RawPacketFrame> frames = data.framesBetween(Duration.ZERO, Duration.ofNanos(100L))
                .toCompletableFuture().join();

        assertEquals(List.of(10L, 20L, 20L, 20L, 30L),
                frames.stream().map(RawPacketFrame::elapsedNanos).toList());
        assertEquals(List.of(0, 0, 1, 2, 0), frames.stream().map(RawPacketFrame::sequence).toList());
    }

    @Test
    void theLeastBufferedTrackDecidesTheProgress() {
        FakeTrack ahead = new FakeTrack(List.of(), List.of());
        ahead.progress = new PlaybackTimeline.BufferProgress(Duration.ofSeconds(4), Duration.ofSeconds(9), true);
        FakeTrack behind = new FakeTrack(List.of(), List.of());
        behind.progress = new PlaybackTimeline.BufferProgress(Duration.ofSeconds(1), Duration.ofSeconds(2), false);

        PlaybackTimeline.BufferProgress progress = data(ahead, behind).progress(Duration.ZERO);

        assertEquals(Duration.ofSeconds(1), progress.bufferedBehind());
        assertEquals(Duration.ofSeconds(2), progress.bufferedAhead());
        assertFalse(progress.minimumResumeSatisfied());
    }

    private static MergedTrackData data(FakeTrack... tracks) {
        List<MergedTrackData.Track> merged = new ArrayList<>();
        for (FakeTrack track : tracks) {
            merged.add(MergedTrackData.Track.of(
                    track, ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L)))));
        }
        return new MergedTrackData(merged);
    }

    private static RawPacketFrame frame(long elapsedNanos, int sequence) {
        return new RawPacketFrame(elapsedNanos, elapsedNanos / 10L, sequence, PacketPhase.PLAY, 1, new byte[0]);
    }

    private static final class FakeTrack implements PlaybackTimeline.PlaybackData {
        private final List<RawPacketFrame> checkpoint;
        private final List<RawPacketFrame> frames;
        private PlaybackTimeline.BufferProgress progress =
                new PlaybackTimeline.BufferProgress(Duration.ZERO, Duration.ZERO, true);
        private SeekPoint requestedCheckpoint;
        private SeekPoint requestedAfter;

        private FakeTrack(List<RawPacketFrame> checkpoint, List<RawPacketFrame> frames) {
            this.checkpoint = checkpoint;
            this.frames = frames;
        }

        @Override
        public CompletionStage<PlaybackTimeline.BufferProgress> ensureAvailable(
                Duration position, PlaybackSpeed speed, PrefetchPlanner.Direction direction) {
            return CompletableFuture.completedFuture(progress);
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
            requestedCheckpoint = point;
            return CompletableFuture.completedFuture(checkpoint);
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesBetween(
                Duration startInclusive, Duration endInclusive) {
            return CompletableFuture.completedFuture(frames);
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesAfter(
                SeekPoint checkpoint, Duration endExclusive) {
            requestedAfter = checkpoint;
            return CompletableFuture.completedFuture(frames);
        }

        @Override
        public PlaybackTimeline.BufferProgress progress(Duration position) {
            return progress;
        }

        @Override
        public void close() {
        }
    }
}
