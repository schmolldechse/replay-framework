package dev.voldechse.replayframework.api.event;

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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Read-only publisher for framework lifecycle events.
 *
 * <p>Events are transient notifications, not a durable journal. The public
 * contract intentionally exposes no publish method.</p>
 */
public interface ReplayEventPublisher {
    /**
     * Subscribes a listener to future events.
     *
     * <p>Implementations must isolate listener failures from framework
     * operations and may invoke listeners off the Paper server thread.</p>
     *
     * @param listener event listener
     * @return idempotent subscription handle
     */
    Subscription subscribe(Consumer<? super ReplayEvent> listener);

    /** Common immutable event contract. */
    sealed interface ReplayEvent
            permits RecordingStatusChanged,
                    PlaybackStatusChanged,
                    PlaybackSpeedChanged,
                    PlaybackSeeked,
                    PlaybackBufferChanged,
                    RecordingCompleted,
                    PlaybackCompleted {
        /**
         * Returns the event creation instant.
         *
         * @return event timestamp
         */
        Instant occurredAt();
    }

    /**
     * Event for one recording status transition.
     *
     * @param sessionId recording session identifier
     * @param replayId replay identifier
     * @param previous previous status
     * @param current current status
     * @param occurredAt event timestamp
     */
    record RecordingStatusChanged(
            RecordingSessionId sessionId,
            ReplayId replayId,
            RecordingStatus previous,
            RecordingStatus current,
            Instant occurredAt) implements ReplayEvent {
        /** Validates that this event represents an actual status transition. */
        public RecordingStatusChanged {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (previous == current) {
                throw new IllegalArgumentException("previous and current status must differ");
            }
        }
    }

    /**
     * Event for one playback status transition.
     *
     * @param sessionId playback session identifier
     * @param replayId replay identifier
     * @param viewerId viewer UUID
     * @param previous previous status
     * @param current current status
     * @param snapshot resulting playback snapshot
     * @param occurredAt event timestamp
     */
    record PlaybackStatusChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackStatus previous,
            PlaybackStatus current,
            PlaybackSnapshot snapshot,
            Instant occurredAt) implements ReplayEvent {
        /** Validates transition identity and snapshot consistency. */
        public PlaybackStatusChanged {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(viewerId, "viewerId");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (previous == current) {
                throw new IllegalArgumentException("previous and current status must differ");
            }
            if (snapshot.status() != current) {
                throw new IllegalArgumentException("snapshot status must match current status");
            }
        }
    }

    /**
     * Event for one session-local speed transition.
     *
     * @param sessionId playback session identifier
     * @param replayId replay identifier
     * @param viewerId viewer UUID
     * @param previous previous speed
     * @param current current speed
     * @param snapshot resulting playback snapshot
     * @param occurredAt event timestamp
     */
    record PlaybackSpeedChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSpeed previous,
            PlaybackSpeed current,
            PlaybackSnapshot snapshot,
            Instant occurredAt) implements ReplayEvent {
        /** Validates speed transition identity and snapshot consistency. */
        public PlaybackSpeedChanged {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(viewerId, "viewerId");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (previous == current) {
                throw new IllegalArgumentException("previous and current speed must differ");
            }
            if (snapshot.speed() != current) {
                throw new IllegalArgumentException("snapshot speed must match current speed");
            }
        }
    }

    /**
     * Event for one applied seek operation.
     *
     * @param sessionId playback session identifier
     * @param replayId replay identifier
     * @param viewerId viewer UUID
     * @param requestedPosition requested position after input normalization
     * @param appliedPosition position applied by the timeline
     * @param snapshot resulting playback snapshot
     * @param occurredAt event timestamp
     */
    record PlaybackSeeked(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            Duration requestedPosition,
            Duration appliedPosition,
            PlaybackSnapshot snapshot,
            Instant occurredAt) implements ReplayEvent {
        /** Validates nonnegative positions and snapshot consistency. */
        public PlaybackSeeked {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(viewerId, "viewerId");
            requireNonNegative(requestedPosition, "requestedPosition");
            requireNonNegative(appliedPosition, "appliedPosition");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (!snapshot.position().equals(appliedPosition)) {
                throw new IllegalArgumentException(
                        "snapshot position must match applied position");
            }
        }
    }

    /**
     * Event for a changed available buffer window.
     *
     * @param sessionId playback session identifier
     * @param replayId replay identifier
     * @param viewerId viewer UUID
     * @param snapshot resulting playback snapshot
     * @param occurredAt event timestamp
     */
    record PlaybackBufferChanged(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot,
            Instant occurredAt) implements ReplayEvent {
        /** Validates event identity and snapshot. */
        public PlaybackBufferChanged {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(viewerId, "viewerId");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * Event for terminal recording completion.
     *
     * @param sessionId recording session identifier
     * @param replayId replay identifier
     * @param finalStatus terminal recording status
     * @param completionReason successful completion reason, when available
     * @param failureCode failure classification, when failed
     * @param occurredAt event timestamp
     */
    record RecordingCompleted(
            RecordingSessionId sessionId,
            ReplayId replayId,
            RecordingStatus finalStatus,
            Optional<ReplayCompletionReason> completionReason,
            Optional<ReplayFailureCode> failureCode,
            Instant occurredAt) implements ReplayEvent {
        /** Validates the mutually exclusive successful and failed outcomes. */
        public RecordingCompleted {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(finalStatus, "finalStatus");
            Objects.requireNonNull(completionReason, "completionReason");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (finalStatus == RecordingStatus.AVAILABLE
                    && (completionReason.isEmpty() || failureCode.isPresent())) {
                throw new IllegalArgumentException(
                        "AVAILABLE completion requires a completion reason only");
            }
            if (finalStatus == RecordingStatus.FAILED
                    && (failureCode.isEmpty() || completionReason.isPresent())) {
                throw new IllegalArgumentException(
                        "FAILED completion requires a failure code only");
            }
            if (finalStatus != RecordingStatus.AVAILABLE
                    && finalStatus != RecordingStatus.FAILED) {
                throw new IllegalArgumentException(
                        "recording completion must be AVAILABLE or FAILED");
            }
        }
    }

    /**
     * Event for terminal playback completion or closure.
     *
     * @param sessionId playback session identifier
     * @param replayId replay identifier
     * @param viewerId viewer UUID
     * @param finalStatus terminal playback status
     * @param snapshot resulting playback snapshot
     * @param failureCode failure classification, when failed
     * @param occurredAt event timestamp
     */
    record PlaybackCompleted(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackStatus finalStatus,
            PlaybackSnapshot snapshot,
            Optional<ReplayFailureCode> failureCode,
            Instant occurredAt) implements ReplayEvent {
        /** Validates terminal status, snapshot and failure-code consistency. */
        public PlaybackCompleted {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(viewerId, "viewerId");
            Objects.requireNonNull(finalStatus, "finalStatus");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (finalStatus != PlaybackStatus.ENDED
                    && finalStatus != PlaybackStatus.FAILED
                    && finalStatus != PlaybackStatus.CLOSED) {
                throw new IllegalArgumentException(
                        "playback completion must be ENDED, FAILED or CLOSED");
            }
            if (snapshot.status() != finalStatus) {
                throw new IllegalArgumentException("snapshot status must match final status");
            }
            if (finalStatus == PlaybackStatus.FAILED && failureCode.isEmpty()) {
                throw new IllegalArgumentException("FAILED playback requires a failure code");
            }
            if (finalStatus != PlaybackStatus.FAILED && failureCode.isPresent()) {
                throw new IllegalArgumentException(
                        "only FAILED playback may carry a failure code");
            }
        }
    }

    /**
     * Removes one event listener. Closing repeatedly has no effect.
     */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private static void requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
