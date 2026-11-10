package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:resource_pack_push}. */
final class ResourcePackPushPacketDefinition extends CatalogPacketDefinition {

    ResourcePackPushPacketDefinition(PacketClassification classification) {
        super("minecraft:resource_pack_push", classification);
    }
}

