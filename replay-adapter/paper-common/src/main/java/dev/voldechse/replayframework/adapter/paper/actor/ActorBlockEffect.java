package dev.voldechse.replayframework.adapter.paper.actor;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Optional;

/**
 * One family of block effects the server sends to everyone except the actor,
 * such as breaking a block or opening a door.
 *
 * <p>A family compares the block before and after the server handled an
 * {@link ActorAction} and rebuilds the packet the server sent to other
 * players. It must mirror the server code it replaces and nothing more.</p>
 *
 * @param <S> the version's block state type
 */
public interface ActorBlockEffect<S> {

    /**
     * Returns the effect of a change at one probed position, or empty when
     * this family does not explain the change.
     *
     * @param kind what the actor did
     * @param position the changed position
     * @param before the block before the server handled the action
     * @param after the block after the server handled the action
     */
    Optional<ActorEffect> effect(
            ActorAction.Kind kind, BlockPosition position, S before, S after);
}
