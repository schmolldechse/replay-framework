package dev.voldechse.replayframework.adapter.paper.actor;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explains the block changes of one {@link ActorAction} with an ordered list
 * of effect families. The first family that explains a change wins, so more
 * specific families (a door opening) come before general ones (a block being
 * placed).
 *
 * @param <S> the version's block state type
 */
public final class ActorBlockEffects<S> {

    private final List<ActorBlockEffect<S>> families;

    public ActorBlockEffects(List<? extends ActorBlockEffect<S>> families) {
        this.families = List.copyOf(Objects.requireNonNull(families, "families"));
    }

    /**
     * Returns the effects of every probed position whose block changed.
     *
     * @param before blocks read before the server handled the action
     * @param after blocks read after the server handled the action
     */
    public List<ActorEffect> effects(
            ActorAction action, Map<BlockPosition, S> before, Map<BlockPosition, S> after) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        List<ActorEffect> effects = new ArrayList<>();
        for (BlockPosition position : action.probedPositions()) {
            S previous = before.get(position);
            S current = after.get(position);
            // Unloaded positions are not read; an unchanged block had no effect.
            if (previous == null || current == null || previous.equals(current)) {
                continue;
            }
            for (ActorBlockEffect<S> family : families) {
                Optional<ActorEffect> effect =
                        family.effect(action.kind(), position, previous, current);
                if (effect.isPresent()) {
                    effects.add(effect.get());
                    break;
                }
            }
        }
        return List.copyOf(effects);
    }
}
