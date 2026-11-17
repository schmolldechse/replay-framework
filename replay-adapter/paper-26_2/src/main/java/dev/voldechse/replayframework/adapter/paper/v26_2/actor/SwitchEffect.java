package dev.voldechse.replayframework.adapter.paper.v26_2.actor;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.actor.ActorBlockEffect;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffect;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Optional;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Pulling a lever or pressing a button by hand. Mirrors {@code LeverBlock}
 * (click at volume 0.3, pitch 0.6 on and 0.5 off) and {@code ButtonBlock}
 * (the type's click-on sound at volume 1 and pitch 1). Only the press of a
 * button bypasses the player; its release is sent to everyone.
 */
final class SwitchEffect implements ActorBlockEffect<BlockState> {

    @Override
    public Optional<ActorEffect> effect(
            ActorAction.Kind kind, BlockPosition position, BlockState before, BlockState after) {
        if (kind != ActorAction.Kind.USE_ON_BLOCK
                || before.getBlock() != after.getBlock()
                || !before.hasProperty(BlockStateProperties.POWERED)
                || before.getValue(BlockStateProperties.POWERED) == after.getValue(BlockStateProperties.POWERED)) {
            return Optional.empty();
        }
        boolean powered = after.getValue(BlockStateProperties.POWERED);
        if (after.getBlock() instanceof LeverBlock) {
            return Optional.of(BlockSounds.at(
                    position, SoundEvents.LEVER_CLICK, 0.3F, powered ? 0.6F : 0.5F));
        }
        if (after.getBlock() instanceof ButtonBlock button && powered) {
            return Optional.of(BlockSounds.at(
                    position, BlockSounds.type(button).buttonClickOn(), 1.0F, 1.0F));
        }
        return Optional.empty();
    }
}
