package dev.voldechse.replayframework.core.event;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackBufferChanged;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackCompleted;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackSeeked;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackSpeedChanged;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.PlaybackStatusChanged;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.RecordingCompleted;
import static dev.voldechse.replayframework.api.event.ReplayEventPublisher.RecordingStatusChanged;

/** Shared asynchronous event source for recording and playback lifecycle events. */
public final class ReplayEventDispatcher implements ReplayEventPublisher, AutoCloseable {
    private final Executor eventExecutor;
    private final Consumer<String> logger;
    private final CopyOnWriteArrayList<SubscriptionImpl> subscriptions =
            new CopyOnWriteArrayList<>();
    private final ConcurrentMap<PlaybackSessionId, Boolean> completedPlaybacks =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<RecordingSessionId, Boolean> completedRecordings =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ReplayEventDispatcher(Executor eventExecutor, Consumer<String> logger) {
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Subscription subscribe(Consumer<? super ReplayEvent> listener) {
        if (closed.get()) {
            throw new IllegalStateException("replay event dispatcher is closed");
        }
        SubscriptionImpl subscription = new SubscriptionImpl(
                Objects.requireNonNull(listener, "listener"));
        subscriptions.add(subscription);
        if (closed.get()) {
            subscription.close();
            throw new IllegalStateException("replay event dispatcher is closed");
        }
        return subscription;
    }

    public void recordingStatusChanged(
            RecordingSessionId sessionId,
            ReplayId replayId,
            RecordingStatus previous,
            RecordingStatus current) {
        publish(new RecordingStatusChanged(
                sessionId, replayId, previous, current, Instant.now()));
    }

    public void recordingCompleted(
            RecordingSessionId sessionId,
            ReplayId replayId,
            RecordingStatus finalStatus,
            Optional<ReplayCompletionReason> completionReason,
            Optional<ReplayFailureCode> failureCode) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (completedRecordings.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            return;
        }
        publish(new RecordingCompleted(
                sessionId,
                replayId,
                finalStatus,
                completionReason,
                failureCode,
                Instant.now()));
    }

    public void playbackStatusChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackStatus previous,
            PlaybackStatus current,
            PlaybackSnapshot snapshot) {
        publish(new PlaybackStatusChanged(
                sessionId, replayId, viewerId, previous, current, snapshot, Instant.now()));
    }

    public void playbackSpeedChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSpeed previous,
            PlaybackSpeed current,
            PlaybackSnapshot snapshot) {
        publish(new PlaybackSpeedChanged(
                sessionId, replayId, viewerId, previous, current, snapshot, Instant.now()));
    }

    public void playbackSeeked(
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

    public void playbackBufferChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot) {
        publish(new PlaybackBufferChanged(
                sessionId, replayId, viewerId, snapshot, Instant.now()));
    }

    public void playbackCompleted(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot,
            Optional<ReplayFailureCode> failureCode) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (completedPlaybacks.putIfAbsent(sessionId, Boolean.TRUE) != null) {
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (SubscriptionImpl subscription : subscriptions) {
                subscription.close();
            }
            subscriptions.clear();
        }
    }

    /** Publishes one already validated event from an internal core component. */
    public void publish(ReplayEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed.get()) {
            return;
        }
        try {
            eventExecutor.execute(() -> notifyListeners(event));
        } catch (RuntimeException failure) {
            logListenerFailure(event, failure);
        }
    }

    private void notifyListeners(ReplayEvent event) {
        for (SubscriptionImpl subscription : List.copyOf(subscriptions)) {
            if (!subscription.active()) {
                continue;
            }
            try {
                subscription.listener().accept(event);
            } catch (RuntimeException failure) {
                logListenerFailure(event, failure);
            }
        }
    }

    private void logListenerFailure(ReplayEvent event, Throwable failure) {
        try {
            logger.accept("replay event listener failed for "
                    + event.getClass().getSimpleName()
                    + "; cause=" + failure.getClass().getSimpleName());
        } catch (RuntimeException ignored) {
            // Observer diagnostics must never become a recording or playback failure.
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
