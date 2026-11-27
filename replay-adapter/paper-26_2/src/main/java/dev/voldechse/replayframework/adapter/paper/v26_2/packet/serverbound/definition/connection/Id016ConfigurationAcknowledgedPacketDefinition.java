package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:configuration_acknowledged}.
 *
 * Confirms the switch into the configuration phase. Connection state only.
 */
final class Id016ConfigurationAcknowledgedPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x10;
    static final String IDENTIFIER = "minecraft:configuration_acknowledged";

    Id016ConfigurationAcknowledgedPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
