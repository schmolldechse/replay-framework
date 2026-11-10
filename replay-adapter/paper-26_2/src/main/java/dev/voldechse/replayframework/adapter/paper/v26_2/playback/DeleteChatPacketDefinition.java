package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:delete_chat}. */
final class DeleteChatPacketDefinition extends CatalogPacketDefinition {

    DeleteChatPacketDefinition(PacketClassification classification) {
        super("minecraft:delete_chat", classification);
    }
}

