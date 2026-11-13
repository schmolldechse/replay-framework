package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:initialize_border}.
 *
 * <p>This packet installs a level's complete world-border state, including an
 * active transition from its current size to its target size. It has no
 * connection-local or entity references, so all finite world data and the
 * remaining transition time are preserved for playback and checkpoints.</p>
 */
final class Id043InitializeBorderPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundInitializeBorderPacket,
        Id043InitializeBorderPacketDefinition.Value> {

    static final int PACKET_ID = 0x2B;
    static final String IDENTIFIER = "minecraft:initialize_border";

    Id043InitializeBorderPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundInitializeBorderPacket.class);
    }

    @Override
    protected Value read(ClientboundInitializeBorderPacket packet) {
        return new Value(
                packet.getNewCenterX(),
                packet.getNewCenterZ(),
                packet.getOldSize(),
                packet.getNewSize(),
                packet.getLerpTime(),
                packet.getNewAbsoluteMaxSize(),
                packet.getWarningBlocks(),
                packet.getWarningTime());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundInitializeBorderPacket rebuild(Value value) {
        // Paper exposes no constructor from these fields; decode the exact
        // 26.2 native wire shape to avoid reconstructing a partial WorldBorder.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeDouble(value.centerX());
            buffer.writeDouble(value.centerZ());
            buffer.writeDouble(value.oldSize());
            buffer.writeDouble(value.newSize());
            buffer.writeVarLong(value.lerpTime());
            buffer.writeVarInt(value.newAbsoluteMaxSize());
            buffer.writeVarInt(value.warningBlocks());
            buffer.writeVarInt(value.warningTime());
            return ClientboundInitializeBorderPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(
            double centerX,
            double centerZ,
            double oldSize,
            double newSize,
            long lerpTime,
            int newAbsoluteMaxSize,
            int warningBlocks,
            int warningTime) {

        Value {
            requireFinite(centerX, "centerX");
            requireFinite(centerZ, "centerZ");
            requireFinite(oldSize, "oldSize");
            requireFinite(newSize, "newSize");
            if (oldSize < 0.0 || newSize < 0.0) {
                throw new IncompatibleAdapterException(
                        "initialize_border sizes must not be negative");
            }
            if (lerpTime < 0L) {
                throw new IncompatibleAdapterException(
                        "initialize_border lerpTime must not be negative");
            }
            if (newAbsoluteMaxSize <= 0) {
                throw new IncompatibleAdapterException(
                        "initialize_border absolute maximum size must be positive");
            }
        }

        private static void requireFinite(double value, String field) {
            if (!Double.isFinite(value)) {
                throw new IncompatibleAdapterException(
                        "initialize_border contains a non-finite " + field);
            }
        }
    }
}
