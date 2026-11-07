package dev.voldechse.replayframework.api.playback;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;

/** Selects a safe target world and optionally an exact observer location. */
public sealed interface ObserverPlacement
        permits ObserverPlacement.Automatic, ObserverPlacement.Fixed {

    /** Uses a replay-derived position in the supplied target world. */
    static ObserverPlacement automatic(World targetWorld) {
        return new Automatic(targetWorld);
    }

    /** Uses an exact developer-supplied location after safety validation. */
    static ObserverPlacement fixed(Location target) {
        return new Fixed(target);
    }

    /** Automatic replay-anchor placement in one loaded Paper world. */
    record Automatic(World targetWorld) implements ObserverPlacement {
        public Automatic {
            Objects.requireNonNull(targetWorld, "targetWorld");
        }
    }

    /** Exact placement with defensive handling of Bukkit's mutable location. */
    final class Fixed implements ObserverPlacement {
        private final Location target;

        private Fixed(Location target) {
            this.target = validate(Objects.requireNonNull(target, "target").clone());
        }

        public Location target() {
            return target.clone();
        }

        private static Location validate(Location value) {
            if (!value.isWorldLoaded() || value.getWorld() == null) {
                throw new IllegalArgumentException("observer target world must be loaded");
            }
            value.checkFinite();
            if (!Float.isFinite(value.getYaw()) || !Float.isFinite(value.getPitch())) {
                throw new IllegalArgumentException("observer target rotation must be finite");
            }
            return value;
        }
    }
}
