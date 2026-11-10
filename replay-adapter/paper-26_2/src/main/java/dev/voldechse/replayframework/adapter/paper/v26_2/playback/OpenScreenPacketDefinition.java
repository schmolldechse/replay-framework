package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:open_screen}. */
final class OpenScreenPacketDefinition extends CatalogPacketDefinition {

    OpenScreenPacketDefinition(PacketClassification classification) {
        super("minecraft:open_screen", classification);
    }
}

