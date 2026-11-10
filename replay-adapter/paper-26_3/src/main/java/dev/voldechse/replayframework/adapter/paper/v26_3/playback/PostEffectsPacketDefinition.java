package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:post_effects}. */
final class PostEffectsPacketDefinition extends CatalogPacketDefinition {

    PostEffectsPacketDefinition(PacketClassification classification) {
        super("minecraft:post_effects", classification);
    }
}

