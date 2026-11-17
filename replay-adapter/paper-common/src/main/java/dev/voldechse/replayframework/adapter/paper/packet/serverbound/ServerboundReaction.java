package dev.voldechse.replayframework.adapter.paper.packet.serverbound;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import java.util.Objects;
import java.util.Optional;

/**
 * What one serverbound packet triggers.
 *
 * @param samplePlayerState whether the sender's own position, rotation and
 *                          equipment may have changed and must be sampled
 * @param swing the hand the sender swung, when the packet reports a swing
 * @param actorAction a block action whose effect the server sends to
 *                    everyone except the sender
 */
public record ServerboundReaction(
        boolean samplePlayerState,
        Optional<SwingHand> swing,
        Optional<ActorAction> actorAction) {

    /** The packet triggers nothing. */
    public static final ServerboundReaction NONE =
            new ServerboundReaction(false, Optional.empty(), Optional.empty());

    /** The sender's own state may have changed. */
    public static final ServerboundReaction PLAYER_STATE =
            new ServerboundReaction(true, Optional.empty(), Optional.empty());

    public ServerboundReaction {
        Objects.requireNonNull(swing, "swing");
        Objects.requireNonNull(actorAction, "actorAction");
    }

    /** The sender swung a hand; its state is sampled together with the swing. */
    public static ServerboundReaction swing(SwingHand hand) {
        return new ServerboundReaction(
                true, Optional.of(Objects.requireNonNull(hand, "hand")), Optional.empty());
    }

    /** The sender acted on a block; its state is sampled as well. */
    public static ServerboundReaction actor(ActorAction action) {
        return new ServerboundReaction(
                true, Optional.empty(), Optional.of(Objects.requireNonNull(action, "action")));
    }

    /** The hand of a swing, independent of a version's hand type. */
    public enum SwingHand {
        MAIN_HAND,
        OFF_HAND
    }
}
