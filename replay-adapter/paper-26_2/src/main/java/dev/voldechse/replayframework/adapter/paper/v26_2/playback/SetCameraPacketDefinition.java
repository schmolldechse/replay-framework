package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:set_camera}. */
final class SetCameraPacketDefinition extends CatalogPacketDefinition {

    SetCameraPacketDefinition(PacketClassification classification) {
        super("minecraft:set_camera", classification);
    }
}

