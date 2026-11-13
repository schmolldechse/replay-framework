package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:commands}.
 *
 * <p>The packet replaces the receiving client's Brigadier command tree. Its
 * root, literal and argument nodes can carry child and redirect references,
 * executable and restricted flags, registry-defined argument data and custom
 * suggestion providers. That graph is permission- and connection-sensitive;
 * applying a recorded copy could expose unavailable commands or desynchronize
 * the observer from the live server. Every recorded graph is therefore dropped
 * before registry-dependent decoding, while the live command tree continues to
 * pass through the outbound gate.</p>
 */
final class Id016CommandsPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x10;
    static final String IDENTIFIER = "minecraft:commands";

    Id016CommandsPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
