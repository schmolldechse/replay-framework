package dev.voldechse.replayframework.adapter.paper.v26_2.actor;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.actor.ActorBlockEffect;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffect;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Optional;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Opening or closing a door, trapdoor or fence gate by hand. Mirrors
 * {@code DoorBlock}, {@code TrapDoorBlock} and {@code FenceGateBlock}: the
 * open or close sound of the block's wood or metal type at volume 1 and pitch
 * {@code random * 0.1 + 0.9}, sent to everyone except the player.
 */
final class HingedBlockEffect implements ActorBlockEffect<BlockState> {

    @Override
    public Optional<ActorEffect> effect(
            ActorAction.Kind kind, BlockPosition position, BlockState before, BlockState after) {
        if (kind != ActorAction.Kind.USE_ON_BLOCK
                || before.getBlock() != after.getBlock()
                || !before.hasProperty(BlockStateProperties.OPEN)
                || before.getValue(BlockStateProperties.OPEN) == after.getValue(BlockStateProperties.OPEN)) {
            return Optional.empty();
        }
        boolean opened = after.getValue(BlockStateProperties.OPEN);
        return sound(after.getBlock(), opened)
                .map(sound -> BlockSounds.at(position, sound, 1.0F, BlockSounds.hingePitch()));
    }

    private static Optional<SoundEvent> sound(Block block, boolean opened) {
        if (block instanceof DoorBlock door) {
            return Optional.of(opened ? door.type().doorOpen() : door.type().doorClose());
        }
        if (block instanceof TrapDoorBlock trapDoor) {
            var type = BlockSounds.type(trapDoor);
            return Optional.of(opened ? type.trapdoorOpen() : type.trapdoorClose());
        }
        if (block instanceof FenceGateBlock gate) {
            var type = BlockSounds.type(gate);
            return Optional.of(opened ? type.fenceGateOpen() : type.fenceGateClose());
        }
        return Optional.empty();
    }
}
