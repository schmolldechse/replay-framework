package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;

/**
 * Viewer-local contract for {@code minecraft:container_set_content}.
 *
 * <p>The packet replaces one connection-local menu state using a VarInt
 * container ID, a VarInt state ID, a positional list of optional item stacks
 * and an independent carried/cursor stack. Each non-empty stack can contain a
 * complete registry-bound data-component patch. Oversized native packets may
 * be replaced on the connection by a sequence of {@code container_set_slot}
 * packets, but both forms still target the recorded client's menu. Replaying
 * either form could overwrite the observer's unrelated inventory, so recorded
 * content is dropped before registry-dependent decoding.</p>
 */
final class Id018ContainerSetContentPacketDefinition
        extends ClientboundPacketDefinition<Paper26_2ReplayPacketRewriter> {

    static final int PACKET_ID = 0x12;
    static final String IDENTIFIER = "minecraft:container_set_content";

    Id018ContainerSetContentPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL));
    }

    /** The content of the recipient's own inventory, window 0, is part of its state; other windows are containers it opened. */
    @Override
    public RecipientState recipientState() {
        return RecipientState.when(
                net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket.class,
                packet -> packet.containerId() == 0);
    }
}
