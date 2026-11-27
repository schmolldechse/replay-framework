package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:select_trade}.
 *
 * Selects a merchant offer. Not part of the scene.
 */
final class Id052SelectTradePacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x34;
    static final String IDENTIFIER = "minecraft:select_trade";

    Id052SelectTradePacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
