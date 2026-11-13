package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;

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
}
