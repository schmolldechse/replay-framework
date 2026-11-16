package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import java.util.UUID;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:forget_level_chunk}.
 *
 * <p>The packet unloads a chunk column from the receiving client's view. Paper
 * encodes its signed X/Z coordinates as one packed long. Preserve the native
 * {@link ChunkPos} exactly and send the recorded unload so stale terrain is
 * removed from the observer's scene; the packet contains no playback identity
 * and its position is also part of checkpointed chunk state.</p>
 */
final class Id037ForgetLevelChunkPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundForgetLevelChunkPacket,
        Id037ForgetLevelChunkPacketDefinition.Value> {

    static final int PACKET_ID = 0x25;
    static final String IDENTIFIER = "minecraft:forget_level_chunk";

    Id037ForgetLevelChunkPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundForgetLevelChunkPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily.CHUNK_LIGHT);
    }

    @Override
    protected Value read(ClientboundForgetLevelChunkPacket packet) {
        return new Value(packet.pos());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        // Do not consult the live world or chunk tracker. The recorded packet
        // is the state change that must be applied to the observer's world.
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundForgetLevelChunkPacket rebuild(Value value) {
        return new ClientboundForgetLevelChunkPacket(value.position());
    }

    record Value(ChunkPos position) {

        Value {
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * A dead player cannot move its view, so a chunk unload on its connection
     * is the server tearing that view down. The teardown state claims it and
     * later releases it only if the respawned view does not cover the chunk.
     */
    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ChunkPos position = ClientboundForgetLevelChunkPacket.class.cast(decodedPacket).pos();
        state.viewTeardown().withholdChunkUnload(connectionId, position.x(), position.z());
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        return state.viewTeardown().tearingDown(connectionId)
                || super.suppressesRecipientState(decodedPacket, connectionId, state, serverTick);
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution.VIEW_MEMBERSHIP;
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.SceneMembership sceneMembership(Object decodedPacket) {
        return dev.voldechse.replayframework.adapter.paper.packet.SceneMembership.chunkLost(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket.class.cast(decodedPacket).pos().x(), net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket.class.cast(decodedPacket).pos().z());
    }
}
