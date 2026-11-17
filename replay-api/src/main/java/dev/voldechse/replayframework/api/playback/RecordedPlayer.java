package dev.voldechse.replayframework.api.playback;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;

/**
 * A player of a replay at the current playback position.
 *
 * @param id the player's profile ID
 * @param name the player's name as the recording knows it
 * @param location where the player is in the replay; empty while the player
 *                 is not part of the scene, for example after logging out
 */
public record RecordedPlayer(UUID id, String name, Optional<Location> location) {

    public RecordedPlayer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
    }

    /** Whether the player is part of the scene at the current position. */
    public boolean inScene() {
        return location.isPresent();
    }

    /**
     * A position in a recorded world.
     *
     * @param world the recorded world
     * @param x block-space X
     * @param y block-space Y
     * @param z block-space Z
     * @param yaw horizontal rotation in degrees
     * @param pitch vertical rotation in degrees
     */
    public record Location(Key world, double x, double y, double z, float yaw, float pitch) {

        public Location {
            Objects.requireNonNull(world, "world");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("recorded location must be finite");
            }
        }
    }
}
