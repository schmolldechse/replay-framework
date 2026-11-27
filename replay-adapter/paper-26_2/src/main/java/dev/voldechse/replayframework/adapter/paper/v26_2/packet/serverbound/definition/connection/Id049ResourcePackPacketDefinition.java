package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:resource_pack}.
 *
 * Reports resource pack status. Connection state only.
 */
final class Id049ResourcePackPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x31;
    static final String IDENTIFIER = "minecraft:resource_pack";

    Id049ResourcePackPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
