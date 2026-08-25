package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapts one internal timeline to the public playback-session contract. */
final class DefaultPlaybackSession implements PlaybackSession {
    private final PlaybackSessionId id;
    private final ReplayId replayId;
    private final UUID viewerId;
    private final PlaybackTimeline timeline;
    private final PlaybackCoordinator coordinator;
    private final AtomicBoolean released = new AtomicBoolean();

    DefaultPlaybackSession(
            PlaybackSessionId id,
            ReplayId replayId,
            UUID viewerId,
            PlaybackTimeline timeline,
            PlaybackCoordinator coordinator) {
        this.id = Objects.requireNonNull(id, "id");
        this.replayId = Objects.requireNonNull(replayId, "replayId");
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public PlaybackSessionId id() {
        return id;
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
        return timeline.snapshot();
    }

    @Override
    public void play() {
        timeline.play();
    }

    @Override
    public void pause() {
        timeline.pause();
    }

    @Override
    public void speed(PlaybackSpeed speed) {
        timeline.speed(Objects.requireNonNull(speed, "speed"));
    }

    @Override
    public CompletionStage<PlaybackSnapshot> restart() {
        return timeline.restart();
    }

    @Override
    public CompletionStage<PlaybackSnapshot> seekTo(Duration position) {
        return timeline.seekTo(Objects.requireNonNull(position, "position"));
    }

    @Override
    public CompletionStage<PlaybackSnapshot> seekBy(Duration delta) {
        return timeline.seekBy(Objects.requireNonNull(delta, "delta"));
    }

    @Override
    public CompletionStage<PlaybackSnapshot> close() {
        CompletionStage<PlaybackSnapshot> closeStage = timeline.closeAsync();
        closeStage.whenComplete((ignored, failure) -> release());
        return closeStage;
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            coordinator.release(id, viewerId);
        }
    }
}
