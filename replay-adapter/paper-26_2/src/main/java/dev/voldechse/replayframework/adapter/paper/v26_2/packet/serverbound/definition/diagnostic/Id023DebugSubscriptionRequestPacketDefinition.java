package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.diagnostic;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;

/**
 * Serverbound contract for {@code minecraft:debug_subscription_request}.
 *
 * Subscribes to debug data. Changes nothing in the scene.
 */
final class Id023DebugSubscriptionRequestPacketDefinition extends ServerboundPacketDefinition {

    static final int PACKET_ID = 0x17;
    static final String IDENTIFIER = "minecraft:debug_subscription_request";

    Id023DebugSubscriptionRequestPacketDefinition() {
        super(PACKET_ID, IDENTIFIER);
    }
}
