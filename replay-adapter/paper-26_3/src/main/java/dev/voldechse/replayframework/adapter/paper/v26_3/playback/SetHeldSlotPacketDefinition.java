package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_held_slot}. */
final class SetHeldSlotPacketDefinition extends CatalogPacketDefinition {

    SetHeldSlotPacketDefinition(PacketClassification classification) {
        super("minecraft:set_held_slot", classification);
    }
}

