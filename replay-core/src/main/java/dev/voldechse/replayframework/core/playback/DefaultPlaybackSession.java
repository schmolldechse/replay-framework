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
    private final StateTracks stateTracks;
    private final SceneTracks sceneTracks;
    private final AtomicBoolean released = new AtomicBoolean();

    DefaultPlaybackSession(
            PlaybackSessionId id,
            ReplayId replayId,
            UUID viewerId,
            PlaybackTimeline timeline,
            PlaybackCoordinator coordinator) {
        this(id, replayId, viewerId, timeline, coordinator, null, null);
    }

    DefaultPlaybackSession(
            PlaybackSessionId id,
            ReplayId replayId,
            UUID viewerId,
            PlaybackTimeline timeline,
            PlaybackCoordinator coordinator,
            StateTracks stateTracks,
            SceneTracks sceneTracks) {
        this.sceneTracks = sceneTracks;
        this.id = Objects.requireNonNull(id, "id");
        this.replayId = Objects.requireNonNull(replayId, "replayId");
        this.viewerId = Objects.requireNonNull(viewerId, "viewerId");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.stateTracks = stateTracks;
    }

    @Override
    public CompletionStage<java.util.Optional<dev.voldechse.replayframework.api.playback.SubjectState>> state(
            dev.voldechse.replayframework.api.playback.RecordedSubject subject) {
        if (stateTracks == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        return stateTracks.state(subject, timeline.snapshot().position());
    }

    @Override
    public java.util.Optional<dev.voldechse.replayframework.api.playback.RecordedEntity> entity(int clientEntityId) {
        return timeline.entity(clientEntityId).map(this::withSubject);
    }

    private dev.voldechse.replayframework.api.playback.RecordedEntity withSubject(
            dev.voldechse.replayframework.api.playback.RecordedEntity entity) {
        return stateTracks == null ? entity : stateTracks.withSubject(entity);
    }

    @Override
    public java.util.List<dev.voldechse.replayframework.api.playback.RecordedEntity> entities() {
        return timeline.entities().stream().map(this::withSubject).toList();
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
    public CompletionStage<java.util.List<dev.voldechse.replayframework.api.playback.RecordedPlayer>> recordedPlayers() {
        if (sceneTracks == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(timeline.recordedPlayers());
        }
        return sceneTracks.players(timeline.snapshot().position());
    }

    @Override
    public net.kyori.adventure.key.Key viewedWorld() {
        return timeline.viewedWorld();
    }

    @Override
    public CompletionStage<PlaybackSnapshot> viewWorld(net.kyori.adventure.key.Key world) {
        Objects.requireNonNull(world, "world");
        if (sceneTracks != null) {
            // The timeline plays the new world's track; the view is rebuilt next.
            sceneTracks.view(world.asString());
        }
        return timeline.viewWorld(world);
    }

    @Override
    public CompletionStage<PlaybackSnapshot> close() {
        CompletionStage<PlaybackSnapshot> closeStage = timeline.closeAsync();
        closeStage.whenComplete((ignored, failure) -> {
            if (stateTracks != null) {
                stateTracks.close();
            }
            if (sceneTracks != null) {
                sceneTracks.close();
            }
            release();
        });
        return closeStage;
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            coordinator.release(id, viewerId);
        }
    }
}
