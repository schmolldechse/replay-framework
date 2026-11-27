package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Replay policy for {@code minecraft:player_look_at}.
 *
 * <p>This directs the recorded player's camera toward a position or entity.
 * It is connection-local camera state, so the shared viewer-local policy drops
 * it before decoding and leaves the observer's view unchanged.</p>
 */
final class Id072PlayerLookAtPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x48;
    static final String IDENTIFIER = "minecraft:player_look_at";

    Id072PlayerLookAtPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
