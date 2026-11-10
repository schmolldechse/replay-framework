package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:mount_screen_open}. */
final class MountScreenOpenPacketDefinition extends CatalogPacketDefinition {

    MountScreenOpenPacketDefinition(PacketClassification classification) {
        super("minecraft:mount_screen_open", classification);
    }
}

