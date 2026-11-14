package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local capture and playback policy for minecraft:update_advancements.
 *
 * <p>The raw packet remains available in the recording. It changes the recorded player's
 * advancement progress and screen visibility, so neutral-observer playback drops it before
 * decoding or forwarding it; otherwise the observer's own advancement state would be replaced.</p>
 */
final class Id130UpdateAdvancementsPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x82;
    static final String IDENTIFIER = "minecraft:update_advancements";

    Id130UpdateAdvancementsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
