package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;

/** Packet implementation for {@code minecraft:low_disk_space_warning}. */
final class LowDiskSpaceWarningPacketDefinition extends CatalogPacketDefinition {

    LowDiskSpaceWarningPacketDefinition(PacketClassification classification) {
        super("minecraft:low_disk_space_warning", classification);
    }
}

