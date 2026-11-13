package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

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
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x18;
    static final String IDENTIFIER = "minecraft:custom_payload";

    Id024CustomPayloadPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.configurable(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
