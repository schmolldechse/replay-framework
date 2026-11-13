package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:container_set_data}.
 *
 * <p>The packet carries a VarInt container ID plus a signed-short property ID
 * and signed-short value. The last two fields have no universal interpretation:
 * their meaning and useful range are defined by the active menu type's ordered
 * data slots (for example processing progress, fuel state, costs or selected
 * options). The packet does not identify that menu type, and container IDs can
 * be reused per connection. Recorded values are therefore dropped before
 * decode instead of being applied to a possibly unrelated observer menu.</p>
 */
final class Id019ContainerSetDataPacketDefinition
        extends PacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x13;
    static final String IDENTIFIER = "minecraft:container_set_data";

    Id019ContainerSetDataPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
