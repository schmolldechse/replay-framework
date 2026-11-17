package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

/**
 * Serverbound contract for {@code minecraft:player_action}.
 *
 * Digging, dropping items, releasing a used item, swapping hands and
 * stabbing. Starting or finishing to dig can break a block; the server
 * sends the break particles and sound to everyone except the digger, so
 * this packet derives them. All other actions may change the sender's own
 * equipment.
 */
final class Id041PlayerActionPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x29;
    static final String IDENTIFIER = "minecraft:player_action";

    Id041PlayerActionPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        ServerboundPlayerActionPacket packet = ServerboundPlayerActionPacket.class.cast(decodedPacket);
        return switch (packet.getAction()) {
            case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                BlockPos pos = packet.getPos();
                yield ServerboundReaction.actor(ActorAction.destroy(
                        new BlockPosition(pos.getX(), pos.getY(), pos.getZ())));
            }
            case ABORT_DESTROY_BLOCK, DROP_ALL_ITEMS, DROP_ITEM, RELEASE_USE_ITEM,
                    SWAP_ITEM_WITH_OFFHAND, STAB -> ServerboundReaction.PLAYER_STATE;
        };
    }
}
