package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:resource_pack}.
 *
 * Reports resource pack status. Connection state only.
 */
final class Id050ResourcePackPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x32;
    static final String IDENTIFIER = "minecraft:resource_pack";

    Id050ResourcePackPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
