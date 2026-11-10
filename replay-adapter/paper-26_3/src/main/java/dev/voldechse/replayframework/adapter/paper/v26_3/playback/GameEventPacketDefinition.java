package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:game_event}. */
final class GameEventPacketDefinition extends CatalogPacketDefinition {

    GameEventPacketDefinition(PacketClassification classification) {
        super("minecraft:game_event", classification, Behavior.GAME_EVENT);
    }
}

