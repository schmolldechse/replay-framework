package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Adapter port for creating complete native-packet checkpoint bundles. */
public interface CheckpointEncoder {

    /**
     * Encodes one complete checkpoint asynchronously.
     *
     * @param request validated checkpoint request
     * @return stage completed with a complete checkpoint or exceptionally
     */
    CompletionStage<ReplayCheckpoint> encode(CheckpointRequest request);

    /**
     * Immutable input for one checkpoint operation.
     *
     * @param scope resolved recording scope
     * @param elapsedNanos session-relative checkpoint time
     * @param serverTick server tick represented by the checkpoint
     * @param kind reason for creating the checkpoint
     */
    record CheckpointRequest(
            RecordingScope scope,
            long elapsedNanos,
            long serverTick,
            CheckpointKind kind) {

        /** Validates checkpoint timing and scope invariants. */
        public CheckpointRequest {
            Objects.requireNonNull(scope, "scope");
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("elapsedNanos must not be negative");
            }
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must not be negative");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Reason why the current checkpoint is required. */
    enum CheckpointKind {
        /** Initial state before the first captured delta. */
        INITIAL,
        /** Periodic state used to bound seek reconstruction. */
        PERIODIC,
        /** State required after a dimension transition. */
        DIMENSION_CHANGE
    }
}
