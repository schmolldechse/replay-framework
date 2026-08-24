package dev.voldechse.replayframework.api.recording;

import java.util.Objects;
import net.kyori.adventure.key.Key;

/**
 * Inclusive, axis-aligned block region in one namespaced world.
 *
 * @param world Adventure key identifying the world
 * @param min inclusive lower corner
 * @param max inclusive upper corner
 */
public record CuboidRegion(Key world, BlockPosition min, BlockPosition max) {

    /**
     * Validates the world and the ordering of all three coordinate axes.
     */
    public CuboidRegion {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("min must not exceed max");
        }
    }
}
