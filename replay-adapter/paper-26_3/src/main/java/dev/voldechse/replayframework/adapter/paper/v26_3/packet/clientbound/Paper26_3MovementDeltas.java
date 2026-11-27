package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.Objects;
import net.minecraft.network.protocol.game.VecDelta;

/**
 * The total displacement of a Paper 26.3 relative movement.
 *
 * <p>A {@link VecDelta} is either one linear step or a list of steps, each
 * relative to the previous one (the client decodes them with a moving base).
 * The total is the sum of all steps, in the protocol's fixed-point unit of
 * 1/4096 block.</p>
 */
public final class Paper26_3MovementDeltas {

    /** Fixed-point units per block. */
    public static final double UNITS_PER_BLOCK = 4096.0;

    private Paper26_3MovementDeltas() {
    }

    /** Returns the total displacement in fixed-point units: x, y, z. */
    public static long[] totalUnits(VecDelta delta) {
        Objects.requireNonNull(delta, "delta");
        if (delta instanceof VecDelta.Linear linear) {
            return new long[] {linear.xa(), linear.ya(), linear.za()};
        }
        if (delta instanceof VecDelta.Stepped stepped) {
            long x = 0;
            long y = 0;
            long z = 0;
            for (VecDelta.Stepped.DeltaStep step : stepped.steps()) {
                x += step.xa();
                y += step.ya();
                z += step.za();
            }
            return new long[] {x, y, z};
        }
        throw new IncompatibleAdapterException(
                "unknown relative movement delta " + delta.getClass().getName());
    }
}
