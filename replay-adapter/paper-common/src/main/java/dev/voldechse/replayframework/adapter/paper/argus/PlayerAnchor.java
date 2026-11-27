package dev.voldechse.replayframework.adapter.paper.argus;

import java.util.Objects;
import java.util.UUID;

/**
 * Where an online player is, as a companion eye follows it.
 *
 * @param id the player's profile ID
 * @param world namespaced key of the player's world
 * @param x block-space X of the player
 * @param y block-space Y of the player
 * @param z block-space Z of the player
 * @param alive whether the player is alive; a dead player's eye stays put
 * @param viewDistance the chunk radius the server sends to the player
 */
public record PlayerAnchor(
        UUID id, String world, double x, double y, double z, boolean alive, int viewDistance) {

    public PlayerAnchor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("player position must be finite");
        }
        viewDistance = Math.max(2, Math.min(32, viewDistance));
    }

    /** The eye that stands where this player stands and sees as far. */
    EyeSpec eye() {
        return new EyeSpec(world, x, y, z, viewDistance);
    }
}
