package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:place_ghost_recipe}. */
final class PlaceGhostRecipePacketDefinition extends CatalogPacketDefinition {

    PlaceGhostRecipePacketDefinition(PacketClassification classification) {
        super("minecraft:place_ghost_recipe", classification);
    }
}

