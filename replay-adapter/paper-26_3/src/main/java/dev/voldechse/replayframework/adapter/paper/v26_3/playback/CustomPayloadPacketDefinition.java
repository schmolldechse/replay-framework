package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:custom_payload}. */
final class CustomPayloadPacketDefinition extends CatalogPacketDefinition {

    CustomPayloadPacketDefinition(PacketClassification classification) {
        super("minecraft:custom_payload", classification);
    }
}

