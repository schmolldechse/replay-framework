package dev.voldechse.replayframework.adapter.paper.actor;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A block action a player reported to the server. The server answers it with
 * effects it sends to everyone except that player, because the player's own
 * client plays them already.
 *
 * @param kind what the player did
 * @param target the block the player aimed at
 * @param placement the neighbor a placed block would occupy, for a use on a
 *                  block face
 */
public record ActorAction(Kind kind, BlockPosition target, Optional<BlockPosition> placement) {

    /** The block actions with effects that bypass their actor. */
    public enum Kind {
        /** A block may have been broken. */
        DESTROY_BLOCK,
        /** A block may have been placed, opened, closed, pulled or pressed. */
        USE_ON_BLOCK
    }

    public ActorAction {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(placement, "placement");
    }

    public static ActorAction destroy(BlockPosition target) {
        return new ActorAction(Kind.DESTROY_BLOCK, target, Optional.empty());
    }

    public static ActorAction useOn(BlockPosition target, BlockPosition placement) {
        return new ActorAction(Kind.USE_ON_BLOCK, target, Optional.of(placement));
    }

    /** Positions whose block is read before and after the server handles the action. */
    public List<BlockPosition> probedPositions() {
        return placement.map(neighbor -> List.of(target, neighbor)).orElseGet(() -> List.of(target));
    }
}
