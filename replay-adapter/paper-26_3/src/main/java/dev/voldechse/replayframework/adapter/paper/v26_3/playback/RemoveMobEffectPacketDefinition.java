package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:remove_mob_effect}. */
final class RemoveMobEffectPacketDefinition extends CatalogPacketDefinition {

    RemoveMobEffectPacketDefinition(PacketClassification classification) {
        super("minecraft:remove_mob_effect", classification, Behavior.IDENTITY);
    }
}

