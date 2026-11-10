package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:pong_response}. */
final class PongResponsePacketDefinition extends CatalogPacketDefinition {

    PongResponsePacketDefinition(PacketClassification classification) {
        super("minecraft:pong_response", classification);
    }
}

