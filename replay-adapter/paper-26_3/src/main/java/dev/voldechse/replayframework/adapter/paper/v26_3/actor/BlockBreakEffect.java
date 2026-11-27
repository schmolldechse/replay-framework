package dev.voldechse.replayframework.adapter.paper.v26_3.actor;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.actor.ActorBlockEffect;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffect;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaking a block. Mirrors {@code Block.spawnDestroyParticles}: level event
 * 2001 with the broken block, which plays its particles and break sound, sent
 * within 64 blocks to everyone except the player who broke it.
 */
final class BlockBreakEffect implements ActorBlockEffect<BlockState> {

    private static final double LEVEL_EVENT_RANGE = 64.0;

    @Override
    public Optional<ActorEffect> effect(
            ActorAction.Kind kind, BlockPosition position, BlockState before, BlockState after) {
        if (kind != ActorAction.Kind.DESTROY_BLOCK
                || before.isAir()
                || before.getBlock() == after.getBlock()) {
            return Optional.empty();
        }
        // Paper 26.3 renamed event 2001, which the server sends when an entity
        // breaks a block (Block.spawnDestroyByEntityParticles), to
        // PARTICLES_AND_SOUND_DESTROY_BLOCK; PARTICLES_DESTROY_BLOCK (2014) is
        // particles without the break sound.
        ClientboundLevelEventPacket packet = new ClientboundLevelEventPacket(
                LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK,
                new BlockPos(position.x(), position.y(), position.z()),
                Block.getId(before),
                false);
        return Optional.of(new ActorEffect(
                packet, position.x(), position.y(), position.z(), LEVEL_EVENT_RANGE));
    }
}
