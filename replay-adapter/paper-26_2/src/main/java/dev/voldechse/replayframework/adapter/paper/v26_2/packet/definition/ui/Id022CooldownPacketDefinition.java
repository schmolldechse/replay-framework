package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:cooldown}.
 *
 * <p>The packet starts, replaces or clears the receiving client's item-use
 * cooldown for one namespaced group. Items may explicitly share a group through
 * their use-cooldown component; otherwise their registered item identifier is
 * the group. Replaying that state could therefore disable the observer's own
 * items or render an unrelated cooldown overlay. Recorded payloads are captured
 * but dropped before decode, while genuine live cooldowns remain untouched.</p>
 */
final class Id022CooldownPacketDefinition
        extends PacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x16;
    static final String IDENTIFIER = "minecraft:cooldown";

    Id022CooldownPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
