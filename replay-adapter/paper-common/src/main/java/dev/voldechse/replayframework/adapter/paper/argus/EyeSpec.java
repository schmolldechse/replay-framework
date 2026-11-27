package dev.voldechse.replayframework.adapter.paper.argus;

import java.util.Objects;

/**
 * Where an eye stands and how far it sees.
 *
 * @param world namespaced world key
 * @param x block-space X of the observation point
 * @param y block-space Y of the observation point
 * @param z block-space Z of the observation point
 * @param viewDistance chunk radius the eye loads, receives and keeps simulated
 */
public record EyeSpec(String world, double x, double y, double z, int viewDistance) {

    public EyeSpec {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("eye position must be finite");
        }
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("viewDistance must be between 2 and 32");
        }
    }
}
