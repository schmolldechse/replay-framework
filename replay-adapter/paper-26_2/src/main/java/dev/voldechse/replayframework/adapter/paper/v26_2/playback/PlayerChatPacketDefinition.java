package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:player_chat}. */
final class PlayerChatPacketDefinition extends CatalogPacketDefinition {

    PlayerChatPacketDefinition(PacketClassification classification) {
        super("minecraft:player_chat", classification, Behavior.RECORDED_CHAT);
    }
}

