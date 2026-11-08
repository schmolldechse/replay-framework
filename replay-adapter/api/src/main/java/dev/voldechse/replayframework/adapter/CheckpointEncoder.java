package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.util.ArrayList;
import java.util.List;
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
     * Captures one checkpoint together with the ordered point represented by
     * its detached state.
     *
     * <p>The compatibility implementation retains the requested point. An
     * adapter whose snapshot is scheduled or performs asynchronous preparation
     * must override this method and report the point at which the state was
     * actually detached.</p>
     *
     * @param request validated checkpoint request
     * @return stage completed with checkpoint frames and their capture barrier
     */
    default CompletionStage<CheckpointCapture> capture(CheckpointRequest request) {
        Objects.requireNonNull(request, "request");
        return encode(request).thenApply(checkpoint -> new CheckpointCapture(
                new CapturePoint(
                        request.requestedCaptureTimeNanos(),
                        request.serverTick(),
                        Integer.MAX_VALUE),
                checkpoint.initializationFrames()));
    }

    /**
     * Immutable input for one checkpoint operation.
     *
     * @param scope resolved recording scope
     * @param elapsedNanos session-relative checkpoint time
     * @param serverTick server tick represented by the checkpoint
     * @param kind reason for creating the checkpoint
     * @param recordingStartCaptureTimeNanos monotonic process-clock origin of
     *     the recording; may be negative because {@link System#nanoTime()} has
     *     an arbitrary origin
     */
    record CheckpointRequest(
            RecordingScope scope,
            long elapsedNanos,
            long serverTick,
            CheckpointKind kind,
            long recordingStartCaptureTimeNanos) {

        /**
         * Compatibility constructor for initial checkpoints and external
         * adapters that do not yet report an actual capture barrier.
         */
        public CheckpointRequest(
                RecordingScope scope,
                long elapsedNanos,
                long serverTick,
                CheckpointKind kind) {
            this(scope, elapsedNanos, serverTick, kind, 0L);
        }

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

        /** Returns the requested point on the monotonic process clock. */
        public long requestedCaptureTimeNanos() {
            try {
                return Math.addExact(recordingStartCaptureTimeNanos, elapsedNanos);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        "checkpoint request capture time overflow", overflow);
            }
        }
    }

    /** Ordered process-clock point at which checkpoint state was detached. */
    record CapturePoint(long captureTimeNanos, long serverTick, int sequence) {
        public CapturePoint {
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must not be negative");
            }
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
        }
    }

    /** Immutable checkpoint frames paired with their actual capture barrier. */
    record CheckpointCapture(CapturePoint point, List<RawPacketFrame> initializationFrames) {
        public CheckpointCapture {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(initializationFrames, "initializationFrames");
            List<RawPacketFrame> copied = new ArrayList<>(initializationFrames.size());
            for (RawPacketFrame frame : initializationFrames) {
                copied.add(Objects.requireNonNull(frame, "initializationFrames contains null"));
            }
            initializationFrames = List.copyOf(copied);
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
