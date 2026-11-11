package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:animate}.
 *
 * <p>Paper 26.3 carries only wake-up, critical-hit and magic-critical-hit
 * effects here; hand swings moved to {@code minecraft:swing_animation}. The
 * action remains a raw unsigned byte so unknown values are preserved without
 * reinterpretation.</p>
 */
final class Id002AnimatePacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundAnimatePacket,
        Id002AnimatePacketDefinition.Value> {

    static final int PACKET_ID = 0x02;
    static final String IDENTIFIER = "minecraft:animate";

    Id002AnimatePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundAnimatePacket.class);
    }

    @Override
    protected Value read(ClientboundAnimatePacket packet) {
        return new Value(packet.getId(), packet.getAction());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.action()));
    }

    @Override
    protected ClientboundAnimatePacket rebuild(Value value) {
        // The native type exposes no constructor for an already rewritten ID.
        // Reusing its stream codec preserves the exact version-bound wire contract.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(value.entityId());
            buffer.writeByte(value.action());
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(int entityId, int action) {

        Value {
            if (action < 0 || action > 0xFF) {
                throw new IncompatibleAdapterException(
                        "animate action is not an unsigned byte: " + action);
            }
        }
    }
}
