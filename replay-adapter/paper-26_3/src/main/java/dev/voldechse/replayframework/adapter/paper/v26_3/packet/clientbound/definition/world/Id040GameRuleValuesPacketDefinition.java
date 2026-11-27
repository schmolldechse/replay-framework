package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:game_rule_values}.
 *
 * <p>Paper 26.3 sends a variable-size map of namespaced game-rule keys and
 * serialized values in response to a privileged request on the receiving
 * connection. The map can include every rule available in the recorded
 * player's level, and therefore describes that player's rule view rather
 * than the observer's world. Preserve the raw packet in recordings, but drop
 * it before playback decoding so no rule value can be applied to the
 * observer.</p>
 */
final class Id040GameRuleValuesPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x28;
    static final String IDENTIFIER = "minecraft:game_rule_values";

    Id040GameRuleValuesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

}
