package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.event.ReplayEventDispatcher;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Core playback adapter for the shared recording/playback event dispatcher. */
final class PlaybackEventDispatcher implements ReplayEventPublisher {
    private final ReplayEventDispatcher delegate;

    PlaybackEventDispatcher(Executor eventExecutor, Consumer<String> logger) {
        this(new ReplayEventDispatcher(eventExecutor, logger));
    }

    PlaybackEventDispatcher(ReplayEventDispatcher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Subscription subscribe(Consumer<? super ReplayEvent> listener) {
        return delegate.subscribe(listener);
    }

    void statusChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackStatus previous,
            PlaybackStatus current,
            PlaybackSnapshot snapshot) {
        delegate.playbackStatusChanged(sessionId, replayId, viewerId, previous, current, snapshot);
    }

    void speedChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSpeed previous,
            PlaybackSpeed current,
            PlaybackSnapshot snapshot) {
        delegate.playbackSpeedChanged(sessionId, replayId, viewerId, previous, current, snapshot);
    }

    void seeked(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            Duration requestedPosition,
            Duration appliedPosition,
            PlaybackSnapshot snapshot) {
        delegate.playbackSeeked(
                sessionId,
                replayId,
                viewerId,
                requestedPosition,
                appliedPosition,
                snapshot);
    }

    void bufferChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot) {
        delegate.playbackBufferChanged(sessionId, replayId, viewerId, snapshot);
    }

    void completed(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot,
            Optional<ReplayFailureCode> failureCode) {
        delegate.playbackCompleted(sessionId, replayId, viewerId, snapshot, failureCode);
    }
}
