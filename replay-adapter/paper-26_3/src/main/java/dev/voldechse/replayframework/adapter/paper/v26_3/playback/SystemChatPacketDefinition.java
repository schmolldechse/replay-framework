package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:system_chat}. */
final class SystemChatPacketDefinition extends CatalogPacketDefinition {

    SystemChatPacketDefinition(PacketClassification classification) {
        super("minecraft:system_chat", classification, Behavior.RECORDED_CHAT);
    }
}

