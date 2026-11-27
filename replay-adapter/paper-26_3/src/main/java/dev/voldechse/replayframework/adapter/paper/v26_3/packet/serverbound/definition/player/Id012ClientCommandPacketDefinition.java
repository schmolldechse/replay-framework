package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:client_command}.
 *
 * Requests a respawn or statistics. A respawn reaches the recording through
 * the clientbound respawn sequence.
 */
final class Id012ClientCommandPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x0C;
    static final String IDENTIFIER = "minecraft:client_command";

    Id012ClientCommandPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
