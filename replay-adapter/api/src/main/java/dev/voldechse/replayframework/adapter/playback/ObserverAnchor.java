package dev.voldechse.replayframework.adapter.playback;

/** Version-neutral position candidate decoded from replay checkpoint state. */
public record ObserverAnchor(
        Kind kind,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        java.util.Optional<String> world) {

    /** Creates an anchor whose recorded world is unknown. */
    public ObserverAnchor(Kind kind, double x, double y, double z, float yaw, float pitch) {
        this(kind, x, y, z, yaw, pitch, java.util.Optional.empty());
    }

    public ObserverAnchor {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("observer anchor must be finite");
        }
        if (world == null) {
            throw new NullPointerException("world");
        }
    }

    public enum Kind {
        PLAYER,
        ENTITY
    }
}
