package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.player;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:set_held_slot}.
 *
 * <p>The VarInt selects the receiving player's active hotbar slot (0–8).
 * Retain the update in recordings but do not replace the observer's selection;
 * the common policy drops it before decoding the slot.</p>
 *
 * <p>Not part of the recipient's state track: the server does not send every
 * change of a player's own inventory or selected slot, so Argus reads both
 * every tick instead (see {@code Paper26_3SubjectStates}).</p>
 */
final class Id107SetHeldSlotPacketDefinition
        extends ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter> {

    static final int PACKET_ID = 0x6B;
    static final String IDENTIFIER = "minecraft:set_held_slot";

    Id107SetHeldSlotPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }
}
