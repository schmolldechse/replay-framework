package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Serializes Paper 26.3 capture coordinates together with their publication. */
final class Paper26_3CaptureOrder {
    private final LongSupplier nanoTime;
    private final LongSupplier serverTick;
    private final Object publicationLock = new Object();
    private long sequenceTick = -1L;
    private int nextSequence;

    Paper26_3CaptureOrder(LongSupplier nanoTime, LongSupplier serverTick) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
    }

    /** Allocates one point and keeps the publication inside the same lock. */
    void publish(Consumer<CheckpointEncoder.CapturePoint> publication) {
        Objects.requireNonNull(publication, "publication");
        synchronized (publicationLock) {
            publication.accept(nextPoint());
        }
    }

    /** Allocates an exclusive checkpoint barrier in publication order. */
    CheckpointEncoder.CapturePoint barrier() {
        synchronized (publicationLock) {
            return nextPoint();
        }
    }

    private CheckpointEncoder.CapturePoint nextPoint() {
        long tick = serverTick.getAsLong();
        if (tick < 0L) {
            throw incompatible("Paper server tick is negative");
        }
        if (tick < sequenceTick) {
            throw incompatible("Paper server tick moved backwards");
        }
        if (tick != sequenceTick) {
            sequenceTick = tick;
            nextSequence = 0;
        }
        if (nextSequence == Integer.MAX_VALUE) {
            throw incompatible("Paper capture sequence overflow");
        }
        return new CheckpointEncoder.CapturePoint(
                nanoTime.getAsLong(), tick, nextSequence++);
    }

    private static IncompatibleAdapterException incompatible(String message) {
        return new IncompatibleAdapterException(message);
    }
}
