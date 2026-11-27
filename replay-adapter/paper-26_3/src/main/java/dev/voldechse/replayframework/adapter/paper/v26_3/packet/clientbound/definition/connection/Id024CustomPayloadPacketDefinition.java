package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;

/**
 * Viewer-local contract for {@code minecraft:custom_payload}.
 *
 * <p>The packet carries a channel identifier followed by channel-defined
 * bytes. Minecraft gives {@code minecraft:brand} a known string schema, while
 * Paper preserves every other plugin or mod payload as opaque data. A matching
 * channel name does not establish compatible handshakes, schemas or client
 * state on the observer's connection, and a payload may cause arbitrary
 * client-side effects. Recorded frames are therefore captured but dropped
 * before decode; genuine live custom payloads remain untouched.</p>
 */
final class Id024CustomPayloadPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x18;
    static final String IDENTIFIER = "minecraft:custom_payload";

    Id024CustomPayloadPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.configurable(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            java.util.UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet =
                net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket.class
                        .cast(decodedPacket);
        net.minecraft.resources.Identifier identifier = packet.payload().type().id();
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts
                .withCustomPayloadChannel(base,
                        net.kyori.adventure.key.Key.key(identifier.toString()));
    }
}
