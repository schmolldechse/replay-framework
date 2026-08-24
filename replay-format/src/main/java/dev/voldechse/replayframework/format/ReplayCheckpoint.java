package dev.voldechse.replayframework.format;

import java.util.List;
import java.util.Objects;

/**
 * Immutable native-packet bundle that reconstructs the replay view at one
 * seek position.
 *
 * @param ordinal stable checkpoint ordinal
 * @param elapsedNanos replay time represented by the checkpoint
 * @param initializationFrames clientbound frames applied for reconstruction
 */
public record ReplayCheckpoint(
        int ordinal,
        long elapsedNanos,
        List<RawPacketFrame> initializationFrames) {

    /** Validates and freezes the native frame bundle. */
    public ReplayCheckpoint {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("elapsedNanos must not be negative");
        }
        Objects.requireNonNull(initializationFrames, "initializationFrames");
        for (RawPacketFrame frame : initializationFrames) {
            Objects.requireNonNull(frame, "initializationFrames contains null");
        }
        for (int index = 1; index < initializationFrames.size(); index++) {
            if (isBefore(initializationFrames.get(index), initializationFrames.get(index - 1))) {
                throw new IllegalArgumentException(
                        "initializationFrames must be ordered by elapsed time, server tick and sequence");
            }
        }
        initializationFrames = List.copyOf(initializationFrames);
    }

    private static boolean isBefore(RawPacketFrame current, RawPacketFrame previous) {
        return current.elapsedNanos() < previous.elapsedNanos()
                || (current.elapsedNanos() == previous.elapsedNanos()
                && current.serverTick() < previous.serverTick())
                || (current.elapsedNanos() == previous.elapsedNanos()
                && current.serverTick() == previous.serverTick()
                && current.sequence() < previous.sequence());
    }
}
