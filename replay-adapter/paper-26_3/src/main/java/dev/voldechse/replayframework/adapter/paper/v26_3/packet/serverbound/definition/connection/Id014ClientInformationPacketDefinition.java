package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.connection;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:client_information}.
 *
 * Language, view distance, skin parts and main hand. Skin parts and main
 * hand reach other recipients as entity metadata through the tracker.
 */
final class Id014ClientInformationPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x0E;
    static final String IDENTIFIER = "minecraft:client_information";

    Id014ClientInformationPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
