package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter boundary for observing raw clientbound packets without exposing
 * connection or Netty types to the core.
 */
public interface CaptureBridge {

    /**
     * Installs the capture callback exactly once for the active connection path.
     *
     * @param sink callback receiving copied packet data
     * @param failureHandler callback for the first fatal sink failure
     * @throws IllegalStateException when a different callback is already installed
     */
    void install(PacketSink sink, FailureHandler failureHandler);

    /** Removes the capture hook; repeated calls are safe. */
    void uninstall();

    /**
     * Returns whether this bridge currently owns an installed capture hook.
     *
     * @return {@code true} while installed
     */
    boolean installed();

    /** Receives one copied packet capture event. */
    @FunctionalInterface
    interface PacketSink {
        /**
         * Accepts a packet without blocking the invoking connection thread.
         *
         * @param packet immutable packet event
         */
        void accept(CapturePacket packet);
    }

    /** Receives the first fatal failure raised while delivering to a sink. */
    @FunctionalInterface
    interface FailureHandler {
        /**
         * Reports a failure without throwing it back into the connection thread.
         *
         * @param cause failure cause
         */
        void onFailure(Throwable cause);
    }

    /**
     * Copied raw packet data plus the monotonic timestamp at which it was seen.
     *
     * @param recipientId connection recipient
     * @param captureTimeNanos monotonic capture timestamp; it may be negative as
     *                         permitted by {@link System#nanoTime()}
     * @param serverTick server tick at capture time
     * @param sequence sequence within the server tick
     * @param phase protocol phase
     * @param packetId adapter-specific clientbound packet ID
     * @param payload payload before transport compression and encryption
     * @param context adapter-decoded semantic facts; unknown values are empty
     */
    record CapturePacket(
            UUID recipientId,
            long captureTimeNanos,
            long serverTick,
            int sequence,
            PacketPhase phase,
            int packetId,
            byte[] payload,
            CaptureContext context) {

        /**
         * Keeps older adapter fixtures source-compatible while explicitly
         * marking their semantic context as unknown. This constructor is not
         * allowed to infer packet meaning from raw payload bytes.
         */
        public CapturePacket(
                UUID recipientId,
                long captureTimeNanos,
                long serverTick,
                int sequence,
                PacketPhase phase,
                int packetId,
                byte[] payload) {
            this(recipientId, captureTimeNanos, serverTick, sequence, phase, packetId,
                    payload, CaptureContext.unknown());
        }

        /** Validates and defensively copies the captured packet data. */
        public CapturePacket {
            Objects.requireNonNull(recipientId, "recipientId");
            if (serverTick < 0L) {
                throw new IllegalArgumentException("serverTick must not be negative");
            }
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            Objects.requireNonNull(phase, "phase");
            if (packetId < 0) {
                throw new IllegalArgumentException("packetId must not be negative");
            }
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
            Objects.requireNonNull(context, "context");
        }

        /**
         * Returns a defensive payload copy.
         *
         * @return copied payload
         */
        @Override
        public byte[] payload() {
            return payload.clone();
        }

        /**
         * Rebases the monotonic capture timestamp to one recording session.
         *
         * @param recordingStartNanos monotonic session start timestamp
         * @return format frame with session-relative elapsed time
         * @throws IllegalArgumentException when the timestamp cannot be rebased
         */
        public RawPacketFrame toFrame(long recordingStartNanos) {
            final long elapsedNanos;
            try {
                elapsedNanos = Math.subtractExact(captureTimeNanos, recordingStartNanos);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("capture time cannot be rebased", exception);
            }
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("capture time precedes recording start");
            }
            return new RawPacketFrame(
                    elapsedNanos,
                    serverTick,
                    sequence,
                    phase,
                    packetId,
                    payload);
        }
    }
}
