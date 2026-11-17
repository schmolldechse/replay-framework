package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;

/**
 * Serverbound contract for {@code minecraft:client_tick_end}.
 *
 * Ends one client tick. It arrives every tick and drives the regular sample
 * of the sender's own state, including changes the server makes without a
 * client action, such as equipment breaking.
 */
final class Id013ClientTickEndPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x0D;
    static final String IDENTIFIER = "minecraft:client_tick_end";

    Id013ClientTickEndPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }

    @Override
    public ServerboundReaction react(Object decodedPacket) {
        super.react(decodedPacket);
        return ServerboundReaction.PLAYER_STATE;
    }
}
