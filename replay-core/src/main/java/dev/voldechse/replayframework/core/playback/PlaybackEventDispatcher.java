package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackBufferChanged;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackCompleted;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackSeeked;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackSpeedChanged;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackStatusChanged;

/** Publishes immutable playback events while isolating observers from playback work. */
final class PlaybackEventDispatcher implements ReplayEventPublisher {

    private final Executor eventExecutor;
    private final Consumer<String> logger;
    private final CopyOnWriteArrayList<SubscriptionImpl> subscriptions =
            new CopyOnWriteArrayList<>();
    private final ConcurrentMap<PlaybackSessionId, Boolean> completedSessions =
            new ConcurrentHashMap<>();

    PlaybackEventDispatcher(Executor eventExecutor, Consumer<String> logger) {
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Subscription subscribe(Consumer<? super ReplayEvent> listener) {
        SubscriptionImpl subscription = new SubscriptionImpl(
                Objects.requireNonNull(listener, "listener"));
        subscriptions.add(subscription);
        return subscription;
    }

    void statusChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackStatus previous,
            PlaybackStatus current,
            PlaybackSnapshot snapshot) {
        publish(new PlaybackStatusChanged(
                sessionId,
                replayId,
                viewerId,
                previous,
                current,
                snapshot,
                Instant.now()));
    }

    void speedChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSpeed previous,
            PlaybackSpeed current,
            PlaybackSnapshot snapshot) {
        publish(new PlaybackSpeedChanged(
                sessionId,
                replayId,
                viewerId,
                previous,
                current,
                snapshot,
                Instant.now()));
    }

    void seeked(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            Duration requestedPosition,
            Duration appliedPosition,
            PlaybackSnapshot snapshot) {
        publish(new PlaybackSeeked(
                sessionId,
                replayId,
                viewerId,
                requestedPosition,
                appliedPosition,
                snapshot,
                Instant.now()));
    }

    void bufferChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot) {
        publish(new PlaybackBufferChanged(
                sessionId,
                replayId,
                viewerId,
                snapshot,
                Instant.now()));
    }

    void completed(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot,
            Optional<ReplayFailureCode> failureCode) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (completedSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            return;
        }
        publish(new PlaybackCompleted(
                sessionId,
                replayId,
                viewerId,
                snapshot.status(),
                snapshot,
                Objects.requireNonNull(failureCode, "failureCode"),
                Instant.now()));
    }

    private void publish(ReplayEvent event) {
        try {
            eventExecutor.execute(() -> notifyListeners(event));
        } catch (RuntimeException exception) {
            logListenerFailure(event, exception);
        }
    }

    private void notifyListeners(ReplayEvent event) {
        for (SubscriptionImpl subscription : List.copyOf(subscriptions)) {
            if (!subscription.active()) {
                continue;
            }
            try {
                subscription.listener().accept(event);
            } catch (RuntimeException exception) {
                logListenerFailure(event, exception);
            }
        }
    }

    private void logListenerFailure(ReplayEvent event, Throwable failure) {
        try {
            logger.accept("playback event listener failed for " + event + ": " + failure);
        } catch (RuntimeException ignored) {
            // Observer diagnostics must never become a playback failure.
        }
    }

    private final class SubscriptionImpl implements Subscription {
        private final Consumer<? super ReplayEvent> listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private SubscriptionImpl(Consumer<? super ReplayEvent> listener) {
            this.listener = listener;
        }

        private Consumer<? super ReplayEvent> listener() {
            return listener;
        }

        private boolean active() {
            return active.get();
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                subscriptions.remove(this);
            }
        }
    }
}
