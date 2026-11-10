package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:custom_chat_completions}. */
final class CustomChatCompletionsPacketDefinition extends CatalogPacketDefinition {

    CustomChatCompletionsPacketDefinition(PacketClassification classification) {
        super("minecraft:custom_chat_completions", classification);
    }
}

