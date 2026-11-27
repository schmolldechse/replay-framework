package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Serverbound contract for {@code minecraft:use_item_on}.
 *
 * Uses an item on a block face. Placing a block, opening a door, trapdoor
 * or fence gate, pulling a lever and pressing a button play their sound to
 * everyone except the sender, so this packet derives them. Known gap: tool,
 * bucket, flint and steel, honeycomb, brush and comparator sounds are not
 * derived yet.
 */
final class Id066UseItemOnPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x42;
    static final String IDENTIFIER = "minecraft:use_item_on";

    Id066UseItemOnPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        BlockHitResult hit = ServerboundUseItemOnPacket.class.cast(decodedPacket).hitResult();
        BlockPos target = hit.getBlockPos();
        BlockPos placement = target.relative(hit.getDirection());
        return ServerboundReaction.actor(ActorAction.useOn(
                new BlockPosition(target.getX(), target.getY(), target.getZ()),
                new BlockPosition(placement.getX(), placement.getY(), placement.getZ())));
    }
}
