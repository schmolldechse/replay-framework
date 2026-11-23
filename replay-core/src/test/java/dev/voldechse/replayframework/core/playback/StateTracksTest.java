package dev.voldechse.replayframework.core.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.format.TrackDescriptor;
import dev.voldechse.replayframework.format.TrackSubject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StateTracksTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    /** Barriers at 0 and 10 seconds. */
    private static final ReplayIndex REFERENCE = ReplayIndex.of(List.of(
            new SeekPoint(0L, 0, 0, 0L), new SeekPoint(10_000_000_000L, 1, 0, 0L)));

    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger checkpointReads = new AtomicInteger();
    private final AtomicInteger decodes = new AtomicInteger();

    @Test
    void readsTheCheckpointOfTheBarrierAndTheFramesUpToThePosition() {
        FakeTrack track = new FakeTrack(
                Map.of(0, List.of(frame(0L)), 1, List.of(frame(10_000_000_000L), frame(10_000_000_000L))),
                List.of(frame(2_000_000_000L), frame(11_000_000_000L), frame(12_000_000_000L)));
        StateTracks states = states(track);

        assertEquals(Optional.of(2), frameCount(states, Duration.ofSeconds(5)));
        assertEquals(Optional.of(3), frameCount(states, Duration.ofMillis(11_500)));
        assertEquals(1, opened.get());
    }

    @Test
    void repeatedQueriesReuseTheCheckpointAndTheDecodedState() {
        FakeTrack track = new FakeTrack(
                Map.of(0, List.of(frame(0L)), 1, List.of(frame(10_000_000_000L))),
                List.of(frame(2_000_000_000L), frame(3_000_000_000L)));
        StateTracks states = states(track);

        assertEquals(Optional.of(2), frameCount(states, Duration.ofMillis(2_500)));
        assertEquals(Optional.of(2), frameCount(states, Duration.ofMillis(2_600)));
        assertEquals(Optional.of(3), frameCount(states, Duration.ofMillis(3_500)));
        assertEquals(1, checkpointReads.get());
        assertEquals(2, decodes.get());

        assertEquals(Optional.of(1), frameCount(states, Duration.ofSeconds(11)));
        assertEquals(2, checkpointReads.get());
    }

    @Test
    void knowsNothingOfUnknownSubjectsOrOutsideTheScope() {
        StateTracks states = states(new FakeTrack(Map.of(0, List.of(), 1, List.of()), List.of()));

        assertEquals(Optional.empty(), frameCount(states, Duration.ofSeconds(5)));
        assertEquals(Optional.empty(), states.state(RecordedSubject.entity(ALEX), Duration.ofSeconds(5))
                .toCompletableFuture().join());
    }

    private Optional<Integer> frameCount(StateTracks states, Duration position) {
        return states.state(RecordedSubject.player(ALEX), position).toCompletableFuture().join()
                .map(state -> state.experience().orElseThrow().level());
    }

    private StateTracks states(FakeTrack track) {
        TrackDescriptor descriptor = TrackDescriptor.state(2, TrackSubject.player(ALEX), "Alex");
        return new StateTracks(
                List.of(TrackDescriptor.global(0), descriptor),
                REFERENCE,
                20_000_000_000L,
                new CountingBridge(),
                opening -> {
                    opened.incrementAndGet();
                    return CompletableFuture.completedFuture(new MergedTrackData.Track(track, Map.of(
                            0, new SeekPoint(0L, 0, 0, 0L),
                            1, new SeekPoint(10_000_000_000L, 1, 0, 0L))));
                });
    }

    private static RawPacketFrame frame(long elapsedNanos) {
        return new RawPacketFrame(elapsedNanos, 0L, 0, PacketPhase.PLAY, 1, new byte[0]);
    }

    /** Reports the number of frames it was given as the experience level. */
    private final class CountingBridge implements PlaybackBridge {
        @Override
        public void setIdentityContext(PlaybackIdentityContext context) {
        }

        @Override
        public void send(RawPacketFrame frame) {
        }

        @Override
        public void resetView() {
        }

        @Override
        public void close() {
        }

        @Override
        public Optional<SubjectState> decodeState(RecordedSubject subject, List<RawPacketFrame> frames) {
            decodes.incrementAndGet();
            return Optional.of(new SubjectState(Map.of(), List.of(), Optional.empty(),
                    Optional.of(new SubjectState.Experience(frames.size(), 0.0F, 0))));
        }
    }

    private final class FakeTrack implements PlaybackTimeline.PlaybackData {
        private final Map<Integer, List<RawPacketFrame>> checkpoints;
        private final List<RawPacketFrame> frames;

        private FakeTrack(Map<Integer, List<RawPacketFrame>> checkpoints, List<RawPacketFrame> frames) {
            this.checkpoints = checkpoints;
            this.frames = frames;
        }

        @Override
        public CompletionStage<PlaybackTimeline.BufferProgress> ensureAvailable(
                Duration position, PlaybackSpeed speed, PrefetchPlanner.Direction direction) {
            return CompletableFuture.completedFuture(
                    new PlaybackTimeline.BufferProgress(Duration.ZERO, Duration.ZERO, true));
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
            checkpointReads.incrementAndGet();
            return CompletableFuture.completedFuture(checkpoints.get(point.checkpointOrdinal()));
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesBetween(Duration start, Duration end) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesAfter(SeekPoint checkpoint, Duration endExclusive) {
            return CompletableFuture.completedFuture(frames.stream()
                    .filter(frame -> frame.elapsedNanos() > checkpoint.elapsedNanos())
                    .filter(frame -> frame.elapsedNanos() < endExclusive.toNanos())
                    .toList());
        }

        @Override
        public PlaybackTimeline.BufferProgress progress(Duration position) {
            return new PlaybackTimeline.BufferProgress(Duration.ZERO, Duration.ZERO, true);
        }

        @Override
        public void close() {
        }
    }
}
