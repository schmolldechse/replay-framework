package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:disguised_chat}. */
final class DisguisedChatPacketDefinition extends CatalogPacketDefinition {

    DisguisedChatPacketDefinition(PacketClassification classification) {
        super("minecraft:disguised_chat", classification, Behavior.RECORDED_CHAT);
    }
}

