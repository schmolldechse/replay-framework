package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:resource_pack_pop}. */
final class ResourcePackPopPacketDefinition extends CatalogPacketDefinition {

    ResourcePackPopPacketDefinition(PacketClassification classification) {
        super("minecraft:resource_pack_pop", classification);
    }
}

