package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local capture and playback policy for minecraft:update_advancements.
 *
 * <p>The raw packet remains available in the recording. It changes the recorded player's
 * advancement progress and screen visibility, so neutral-observer playback drops it before
 * decoding or forwarding it; otherwise the observer's own advancement state would be replaced.</p>
 */
final class Id133UpdateAdvancementsPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x85;
    static final String IDENTIFIER = "minecraft:update_advancements";

    Id133UpdateAdvancementsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
