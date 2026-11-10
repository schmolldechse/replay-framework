package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:commands}. */
final class CommandsPacketDefinition extends CatalogPacketDefinition {

    CommandsPacketDefinition(PacketClassification classification) {
        super("minecraft:commands", classification);
    }
}

