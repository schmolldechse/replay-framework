package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_score}. */
final class SetScorePacketDefinition extends CatalogPacketDefinition {

    SetScorePacketDefinition(PacketClassification classification) {
        super("minecraft:set_score", classification);
    }
}

