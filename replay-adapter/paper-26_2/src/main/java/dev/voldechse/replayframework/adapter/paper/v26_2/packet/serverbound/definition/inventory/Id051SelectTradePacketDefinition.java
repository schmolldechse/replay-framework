package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:select_trade}.
 *
 * Selects a merchant offer. Not part of the scene.
 */
final class Id051SelectTradePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x33;
    static final String IDENTIFIER = "minecraft:select_trade";

    Id051SelectTradePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
