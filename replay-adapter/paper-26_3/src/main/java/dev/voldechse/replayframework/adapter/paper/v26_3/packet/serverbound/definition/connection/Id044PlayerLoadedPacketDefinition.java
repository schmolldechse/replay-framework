package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:player_loaded}.
 *
 * Reports that the client finished loading. Connection state only.
 */
final class Id044PlayerLoadedPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x2C;
    static final String IDENTIFIER = "minecraft:player_loaded";

    Id044PlayerLoadedPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
