package dev.voldechse.replayframework.adapter.paper.v26_2.actor;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.actor.ActorBlockEffect;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffect;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Optional;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Placing a block. Mirrors {@code BlockItem.place}: the placed block's place
 * sound at volume {@code (volume + 1) / 2} and pitch {@code pitch * 0.8},
 * sent to everyone except the player who placed it.
 */
final class BlockPlaceEffect implements ActorBlockEffect<BlockState> {

    @Override
    public Optional<ActorEffect> effect(
            ActorAction.Kind kind, BlockPosition position, BlockState before, BlockState after) {
        // A block can only be placed where nothing solid was: air, a fluid
        // or something replaceable such as grass.
        boolean free = before.isAir() || before.canBeReplaced() || !before.getFluidState().isEmpty();
        if (kind != ActorAction.Kind.USE_ON_BLOCK
                || !free
                || after.isAir()
                || before.getBlock() == after.getBlock()) {
            return Optional.empty();
        }
        SoundType sound = after.getSoundType();
        return Optional.of(BlockSounds.at(
                position,
                sound.getPlaceSound(),
                (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * 0.8F));
    }
}
