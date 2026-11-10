package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:block_destruction}. */
final class BlockDestructionPacketDefinition extends CatalogPacketDefinition {

    BlockDestructionPacketDefinition(PacketClassification classification) {
        super("minecraft:block_destruction", classification, Behavior.IDENTITY);
    }
}

