package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlaybackTimelineFixtureTest {

    @Test
    void speedChangeCommitsPositionWithoutJump() {
        AtomicLong nowNanos = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(
                20_000_000_000L,
                PlaybackSpeed.NORMAL,
                nowNanos::get);

        clock.play(nowNanos.get());
        nowNanos.set(4_000_000_000L);
        assertEquals(4_000_000_000L, clock.positionNanos(nowNanos.get()));

        clock.setSpeed(PlaybackSpeed.QUADRUPLE, nowNanos.get());
        assertEquals(4_000_000_000L, clock.positionNanos(nowNanos.get()));

        nowNanos.set(4_500_000_000L);
        assertEquals(6_000_000_000L, clock.positionNanos(nowNanos.get()));
    }

    @Test
    void supportedSpeedsAdvanceAtTheirDeclaredRates() {
        Map<PlaybackSpeed, Long> expectedPositions = Map.of(
                PlaybackSpeed.QUARTER, 500_000_000L,
                PlaybackSpeed.HALF, 1_000_000_000L,
                PlaybackSpeed.NORMAL, 2_000_000_000L,
                PlaybackSpeed.DOUBLE, 4_000_000_000L,
                PlaybackSpeed.QUADRUPLE, 8_000_000_000L);

        expectedPositions.forEach((speed, expectedPosition) -> {
            AtomicLong nowNanos = new AtomicLong();
            PlaybackClock clock = new PlaybackClock(20_000_000_000L, speed, nowNanos::get);
            clock.play(nowNanos.get());
            nowNanos.set(2_000_000_000L);

            assertEquals(expectedPosition, clock.positionNanos(nowNanos.get()));
        });
    }

    @Test
    void seekResetsViewBeforeCheckpointAndDeltaFrames() {
        Duration duration = Duration.ofSeconds(20L);
        ReplayIndex index = ReplayIndex.of(List.of(
                new SeekPoint(0L, 0, 0, 0L),
                new SeekPoint(5_000_000_000L, 1, 1, 16L)));
        RawPacketFrame checkpointFrame = frame(0L, 1, 1);
        RawPacketFrame deltaFrame = frame(6_000_000_000L, 2, 2);
        FakePlaybackData data = new FakePlaybackData(
                List.of(checkpointFrame),
                List.of(deltaFrame));
        RecordingBridge bridge = new RecordingBridge();
        SeekEngine engine = new SeekEngine(
                index,
                duration,
                data,
                bridge,
                Runnable::run);

        SeekEngine.SeekResult result = engine
                .seekTo(Duration.ofSeconds(6L), PlaybackSpeed.NORMAL, false)
                .toCompletableFuture()
                .join();

        assertEquals(6_000_000_000L, result.positionNanos());
        assertEquals(List.of("reset", checkpointFrame, deltaFrame), bridge.calls);
        assertTrue(result.paused());
    }

    @Test
    void seekWaitsForAvailabilityAndClampsTargetToDuration() {
        Duration duration = Duration.ofSeconds(20L);
        ReplayIndex index = ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L)));
        CompletableFuture<PlaybackTimeline.BufferProgress> availability = new CompletableFuture<>();
        FakePlaybackData data = new FakePlaybackData(
                List.of(),
                List.of(),
                availability);
        RecordingBridge bridge = new RecordingBridge();
        SeekEngine engine = new SeekEngine(
                index,
                duration,
                data,
                bridge,
                Runnable::run);

        CompletionStage<SeekEngine.SeekResult> result = engine
                .seekTo(Duration.ofSeconds(30L), PlaybackSpeed.NORMAL, true);

        assertTrue(!result.toCompletableFuture().isDone());
        assertEquals(List.of(), bridge.calls);

        availability.complete(new PlaybackTimeline.BufferProgress(
                Duration.ZERO,
                Duration.ofSeconds(20L)));

        SeekEngine.SeekResult applied = result.toCompletableFuture().join();
        assertEquals(20_000_000_000L, applied.positionNanos());
        assertTrue(applied.paused());
        assertEquals(List.of("reset"), bridge.calls);
    }

    @Test
    void schedulerSendsDueFramesInOrderWithoutDuplicateFrames() {
        AtomicLong nowNanos = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(
                20_000_000_000L,
                PlaybackSpeed.NORMAL,
                nowNanos::get);
        clock.play(nowNanos.get());
        RawPacketFrame first = frame(0L, 1, 2);
        RawPacketFrame second = frame(1_000_000_000L, 1, 1);
        RawPacketFrame third = frame(2_000_000_000L, 2, 1);
        RawPacketFrame future = frame(3_000_000_000L, 3, 1);
        FakePlaybackData data = new FakePlaybackData(
                List.of(),
                List.of(future, third, first, second));
        RecordingBridge bridge = new RecordingBridge();
        PacketScheduler scheduler = new PacketScheduler(
                clock,
                data,
                bridge,
                new NoopSchedulerListener());
        scheduler.runOnce(2_000_000_000L);

        assertEquals(List.of(first, second, third), bridge.frames());

        scheduler.runOnce(3_000_000_000L);

        assertEquals(List.of(first, second, third, future), bridge.frames());
    }

    @Test
    void schedulerDoesNotSendFramesBeforeAvailabilityCompletes() {
        AtomicLong nowNanos = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(
                20_000_000_000L,
                PlaybackSpeed.NORMAL,
                nowNanos::get);
        clock.play(nowNanos.get());
        RawPacketFrame due = frame(1_000_000_000L, 1, 1);
        CompletableFuture<PlaybackTimeline.BufferProgress> availability = new CompletableFuture<>();
        FakePlaybackData data = new FakePlaybackData(
                List.of(),
                List.of(due),
                availability);
        RecordingBridge bridge = new RecordingBridge();
        PacketScheduler scheduler = new PacketScheduler(
                clock,
                data,
                bridge,
                new NoopSchedulerListener());

        scheduler.runOnce(1_000_000_000L);
        assertEquals(List.of(), bridge.frames());

        availability.complete(new PlaybackTimeline.BufferProgress(
                Duration.ZERO,
                Duration.ofSeconds(3L)));
        scheduler.runOnce(1_000_000_000L);

        assertEquals(List.of(due), bridge.frames());
    }

    @Test
    void preparationBuildsInitialViewAndPausePreservesTimelineState() {
        AtomicLong nowNanos = new AtomicLong();
        Duration duration = Duration.ofSeconds(20L);
        RawPacketFrame initial = frame(0L, 1, 1);
        FakePlaybackData data = new FakePlaybackData(List.of(initial), List.of());
        RecordingBridge bridge = new RecordingBridge();
        PlaybackTimeline timeline = new PlaybackTimeline(
                duration,
                ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L))),
                data,
                bridge,
                PlaybackBufferOptions.builder()
                        .minimumResumeBuffer(Duration.ofSeconds(1L))
                        .build(),
                Runnable::run,
                nowNanos::get);

        assertEquals(PlaybackStatus.PAUSED, timeline.prepare().toCompletableFuture().join().status());
        assertEquals(List.of("reset", initial), bridge.calls);

        timeline.play();
        nowNanos.set(1_000_000_000L);
        timeline.runSchedulerOnce(nowNanos.get());
        assertEquals(PlaybackStatus.PLAYING, timeline.snapshot().status());

        timeline.pause();
        assertEquals(PlaybackStatus.PAUSED, timeline.snapshot().status());
        timeline.closeAsync().toCompletableFuture().join();
    }

    @Test
    void bufferingFreezesPositionUntilDataIsReady() {
        AtomicLong nowNanos = new AtomicLong();
        Duration duration = Duration.ofSeconds(20L);
        RawPacketFrame initial = frame(0L, 1, 1);
        CompletableFuture<PlaybackTimeline.BufferProgress> availability =
                CompletableFuture.completedFuture(new PlaybackTimeline.BufferProgress(
                        Duration.ZERO,
                        Duration.ofSeconds(20L)));
        FakePlaybackData data = new FakePlaybackData(List.of(initial), List.of(), availability);
        RecordingBridge bridge = new RecordingBridge();
        PlaybackTimeline timeline = new PlaybackTimeline(
                duration,
                ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L))),
                data,
                bridge,
                PlaybackBufferOptions.builder().build(),
                Runnable::run,
                nowNanos::get);

        timeline.prepare().toCompletableFuture().join();
        CompletableFuture<PlaybackTimeline.BufferProgress> pending = new CompletableFuture<>();
        data.setAvailability(pending);
        timeline.play();
        nowNanos.set(1_000_000_000L);
        timeline.runSchedulerOnce(nowNanos.get());

        nowNanos.set(5_000_000_000L);
        assertEquals(PlaybackStatus.BUFFERING, timeline.snapshot().status());
        assertEquals(Duration.ofSeconds(1L), timeline.snapshot().position());

        pending.complete(new PlaybackTimeline.BufferProgress(
                Duration.ZERO,
                Duration.ofSeconds(20L)));
        timeline.runSchedulerOnce(nowNanos.get());

        assertEquals(PlaybackStatus.PLAYING, timeline.snapshot().status());
        assertEquals(Duration.ofSeconds(1L), timeline.snapshot().position());
        timeline.closeAsync().toCompletableFuture().join();
    }

    private static RawPacketFrame frame(long elapsedNanos, long serverTick, int sequence) {
        return new RawPacketFrame(
                elapsedNanos,
                serverTick,
                sequence,
                PacketPhase.PLAY,
                1,
                new byte[] {(byte) sequence});
    }

    private static final class RecordingBridge implements PlaybackBridge {
        private final List<Object> calls = new ArrayList<>();

        @Override
        public void send(RawPacketFrame frame) {
            calls.add(frame);
        }

        @Override
        public void resetView() {
            calls.add("reset");
        }

        @Override
        public void close() {
            calls.add("close");
        }

        private List<RawPacketFrame> frames() {
            return calls.stream()
                    .filter(RawPacketFrame.class::isInstance)
                    .map(RawPacketFrame.class::cast)
                    .toList();
        }
    }

    private static final class NoopSchedulerListener implements PacketScheduler.Listener {
        @Override
        public void onBuffering() {
        }

        @Override
        public void onBufferReady() {
        }

        @Override
        public boolean shouldContinueBuffering() {
            return true;
        }

        @Override
        public void onEnded() {
        }

        @Override
        public void onFailure(Throwable failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class FakePlaybackData implements PlaybackTimeline.PlaybackData {
        private final List<RawPacketFrame> checkpointFrames;
        private final List<RawPacketFrame> deltaFrames;
        private volatile CompletableFuture<PlaybackTimeline.BufferProgress> availability;

        private FakePlaybackData(
                List<RawPacketFrame> checkpointFrames,
                List<RawPacketFrame> deltaFrames) {
            this(
                    checkpointFrames,
                    deltaFrames,
                    CompletableFuture.completedFuture(new PlaybackTimeline.BufferProgress(
                            Duration.ZERO,
                            Duration.ofSeconds(20L))));
        }

        private FakePlaybackData(
                List<RawPacketFrame> checkpointFrames,
                List<RawPacketFrame> deltaFrames,
                CompletableFuture<PlaybackTimeline.BufferProgress> availability) {
            this.checkpointFrames = List.copyOf(checkpointFrames);
            this.deltaFrames = List.copyOf(deltaFrames);
            this.availability = availability;
        }

        private void setAvailability(CompletableFuture<PlaybackTimeline.BufferProgress> availability) {
            this.availability = availability;
        }

        @Override
        public CompletionStage<PlaybackTimeline.BufferProgress> ensureAvailable(
                Duration position,
                PlaybackSpeed speed,
                dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner.Direction direction) {
            return availability;
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
            return CompletableFuture.completedFuture(checkpointFrames);
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesBetween(
                Duration startInclusive,
                Duration endInclusive) {
            return CompletableFuture.completedFuture(deltaFrames);
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
    }
}
