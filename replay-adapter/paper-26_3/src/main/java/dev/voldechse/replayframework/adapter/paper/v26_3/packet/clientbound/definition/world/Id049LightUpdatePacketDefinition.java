package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:light_update}.
 *
 * <p>Each data mask bit is paired with a 2048-byte nibble array in section
 * order; the matching empty mask explicitly clears a section, and a bit in
 * neither mask leaves it unchanged. These deltas alter persistent chunk light
 * state, so all four masks and both ordered array lists are checkpoint state.</p>
 */
final class Id049LightUpdatePacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundLightUpdatePacket,
        Id049LightUpdatePacketDefinition.Value> {

    static final int PACKET_ID = 0x31;
    static final String IDENTIFIER = "minecraft:light_update";
    private static final int LIGHT_ARRAY_BYTES = 2048;

    Id049LightUpdatePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundLightUpdatePacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.CHUNK_LIGHT);
    }

    @Override
    protected Value read(ClientboundLightUpdatePacket packet) {
        ClientboundLightUpdatePacketData light = packet.lightData();
        return new Value(
                packet.x(),
                packet.z(),
                light.skyYMask(),
                light.blockYMask(),
                light.emptySkyYMask(),
                light.emptyBlockYMask(),
                light.skyUpdates(),
                light.blockUpdates());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundLightUpdatePacket rebuild(Value value) {
        // Paper 26.3 makes the light data a constructible record; the value's
        // accessors hand out copies, so the packet owns its masks and arrays.
        return new ClientboundLightUpdatePacket(
                value.chunkX(),
                value.chunkZ(),
                new ClientboundLightUpdatePacketData(
                        value.skyMask(),
                        value.blockMask(),
                        value.emptySkyMask(),
                        value.emptyBlockMask(),
                        value.skyUpdates(),
                        value.blockUpdates()));
    }

    record Value(
            int chunkX,
            int chunkZ,
            BitSet skyMask,
            BitSet blockMask,
            BitSet emptySkyMask,
            BitSet emptyBlockMask,
            List<byte[]> skyUpdates,
            List<byte[]> blockUpdates) {

        Value {
            skyMask = copyMask(skyMask, "skyMask");
            blockMask = copyMask(blockMask, "blockMask");
            emptySkyMask = copyMask(emptySkyMask, "emptySkyMask");
            emptyBlockMask = copyMask(emptyBlockMask, "emptyBlockMask");
            skyUpdates = copyArrays(skyUpdates, "skyUpdates");
            blockUpdates = copyArrays(blockUpdates, "blockUpdates");
            validateLayer(skyMask, emptySkyMask, skyUpdates, "sky");
            validateLayer(blockMask, emptyBlockMask, blockUpdates, "block");
        }

        @Override
        public BitSet skyMask() {
            return copyMask(skyMask, "skyMask");
        }

        @Override
        public BitSet blockMask() {
            return copyMask(blockMask, "blockMask");
        }

        @Override
        public BitSet emptySkyMask() {
            return copyMask(emptySkyMask, "emptySkyMask");
        }

        @Override
        public BitSet emptyBlockMask() {
            return copyMask(emptyBlockMask, "emptyBlockMask");
        }

        @Override
        public List<byte[]> skyUpdates() {
            return copyArrays(skyUpdates, "skyUpdates");
        }

        @Override
        public List<byte[]> blockUpdates() {
            return copyArrays(blockUpdates, "blockUpdates");
        }

        private static BitSet copyMask(BitSet mask, String field) {
            if (mask == null) {
                throw new IncompatibleAdapterException(
                        "light_update " + field + " must not be null");
            }
            return (BitSet) mask.clone();
        }

        private static List<byte[]> copyArrays(List<byte[]> arrays, String field) {
            if (arrays == null) {
                throw new IncompatibleAdapterException(
                        "light_update " + field + " must not be null");
            }
            ArrayList<byte[]> copy = new ArrayList<>(arrays.size());
            for (byte[] array : arrays) {
                if (array == null || array.length != LIGHT_ARRAY_BYTES) {
                    throw new IncompatibleAdapterException(
                            "light_update " + field + " entries must contain exactly "
                                    + LIGHT_ARRAY_BYTES + " bytes");
                }
                copy.add(array.clone());
            }
            return List.copyOf(copy);
        }

        private static void validateLayer(
                BitSet dataMask,
                BitSet emptyMask,
                List<byte[]> updates,
                String layer) {
            BitSet overlap = (BitSet) dataMask.clone();
            overlap.and(emptyMask);
            if (!overlap.isEmpty()) {
                throw new IncompatibleAdapterException(
                        "light_update " + layer + " data and empty masks overlap");
            }
            if (dataMask.cardinality() != updates.size()) {
                throw new IncompatibleAdapterException(
                        "light_update " + layer + " data mask must have one array per set bit");
            }
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
