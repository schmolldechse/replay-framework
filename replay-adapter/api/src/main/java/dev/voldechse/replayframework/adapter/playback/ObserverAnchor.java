package dev.voldechse.replayframework.adapter.playback;

/** Version-neutral position candidate decoded from replay checkpoint state. */
public record ObserverAnchor(
        Kind kind,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {

    public ObserverAnchor {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("observer anchor must be finite");
        }
    }

    public enum Kind {
        PLAYER,
        ENTITY
    }
}
