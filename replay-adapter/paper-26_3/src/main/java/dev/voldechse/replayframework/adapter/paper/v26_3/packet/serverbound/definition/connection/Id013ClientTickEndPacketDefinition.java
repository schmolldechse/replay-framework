package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:client_tick_end}.
 *
 * Ends one client tick. Connection state only.
 */
final class Id013ClientTickEndPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x0D;
    static final String IDENTIFIER = "minecraft:client_tick_end";

    Id013ClientTickEndPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
