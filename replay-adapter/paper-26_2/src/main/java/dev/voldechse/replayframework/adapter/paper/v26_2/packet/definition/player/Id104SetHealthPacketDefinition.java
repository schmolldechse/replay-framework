package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;

/**
 * Viewer-local contract for {@code minecraft:set_health}.
 *
 * <p>The health Float, food VarInt, and saturation Float target the receiving
 * player's own vitals HUD. Keep them in recordings but do not overwrite the
 * observer's live state. The common packet policy drops the frame before
 * decoding any field.</p>
 */
final class Id104SetHealthPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x68;
    static final String IDENTIFIER = "minecraft:set_health";

    Id104SetHealthPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }


    /**
     * Health at or below zero is the connection-local proof that the
     * receiving player died. The unloads that follow on this connection tear
     * down the dead player's view and are not scene changes.
     */
    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ClientboundSetHealthPacket packet = ClientboundSetHealthPacket.class.cast(decodedPacket);
        if (packet.getHealth() <= 0.0F) {
            state.viewTeardown().playerDied(
                    connectionId, state.world(connectionId).orElse(null));
        }
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }
}
