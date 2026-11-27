package dev.voldechse.replayframework.adapter.paper.packet.serverbound;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import java.util.Objects;
import java.util.Optional;

/**
 * What one serverbound packet triggers.
 *
 * <p>Everything a packet changes about its sender's own entity reaches the
 * recording through the eye that follows the sender. A packet only triggers
 * something when the server answers it with an effect it sends to everyone
 * except the sender.</p>
 *
 * @param actorAction a block action whose effect the server sends to
 *                    everyone except the sender
 */
public record ServerboundReaction(Optional<ActorAction> actorAction) {

    /** The packet triggers nothing. */
    public static final ServerboundReaction NONE = new ServerboundReaction(Optional.empty());

    public ServerboundReaction {
        Objects.requireNonNull(actorAction, "actorAction");
    }

    /** The sender acted on a block. */
    public static ServerboundReaction actor(ActorAction action) {
        return new ServerboundReaction(Optional.of(Objects.requireNonNull(action, "action")));
    }
}
