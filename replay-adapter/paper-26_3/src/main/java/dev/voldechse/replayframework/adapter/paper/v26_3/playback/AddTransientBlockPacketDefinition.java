package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:add_transient_block}. */
final class AddTransientBlockPacketDefinition extends CatalogPacketDefinition {

    AddTransientBlockPacketDefinition(PacketClassification classification) {
        super("minecraft:add_transient_block", classification);
    }
}

