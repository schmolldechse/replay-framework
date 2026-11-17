package dev.voldechse.replayframework.adapter.paper.actor;

import java.util.Objects;

/**
 * The clientbound packet the server sent to everyone except the actor.
 *
 * @param packet native clientbound packet
 * @param x block-space X the server measures hearing distance from
 * @param y block-space Y the server measures hearing distance from
 * @param z block-space Z the server measures hearing distance from
 * @param range the distance within which other players receive the packet
 */
public record ActorEffect(Object packet, double x, double y, double z, double range) {

    public ActorEffect {
        Objects.requireNonNull(packet, "packet");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(range) || range <= 0.0) {
            throw new IllegalArgumentException("effect position and range must be finite");
        }
    }
}
