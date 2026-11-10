package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:update_mob_effect}. */
final class UpdateMobEffectPacketDefinition extends CatalogPacketDefinition {

    UpdateMobEffectPacketDefinition(PacketClassification classification) {
        super("minecraft:update_mob_effect", classification, Behavior.IDENTITY);
    }
}

