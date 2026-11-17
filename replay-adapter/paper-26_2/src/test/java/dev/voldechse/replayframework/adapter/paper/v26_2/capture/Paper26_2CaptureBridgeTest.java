package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_2CaptureBridgeTest {

    @BeforeAll
    static void bootstrapPaperRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.server.Bootstrap.validate();
    }

    @Test
    void extractsTheExactBlockUpdatePositionForSpatialCapture() {
        PacketDescriptor descriptor = new PacketDescriptor(
                "play.clientbound.minecraft:block_update",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                8,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "test/block_update",
                PacketDescriptor.PlaybackScope.SAFE);
        BlockPos position = new BlockPos(-33_554_432, 2_047, 33_554_431);

        var context = captureContext(
                new ClientboundBlockUpdatePacket(position, Blocks.WATER.defaultBlockState()),
                descriptor,
                Key.key("minecraft:overworld"));

        assertEquals(
                Optional.of(new BlockPosition(-33_554_432, 2_047, 33_554_431)),
                context.position());
    }

    @Test
    void extractsTheExactBlockEventPositionForSpatialCapture() {
        PacketDescriptor descriptor = new PacketDescriptor(
                "play.clientbound.minecraft:block_event",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                7,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "test/block_event",
                PacketDescriptor.PlaybackScope.SAFE);
        BlockPos position = new BlockPos(33_554_431, -2_048, -33_554_432);

        var context = captureContext(
                new ClientboundBlockEventPacket(position, Blocks.NOTE_BLOCK, 0, 24),
                descriptor,
                Key.key("minecraft:overworld"));

        assertEquals(
                Optional.of(new BlockPosition(33_554_431, -2_048, -33_554_432)),
                context.position());
    }

    @Test
    void extractsTheExactBlockEntityPositionForSpatialCapture() {
        PacketDescriptor descriptor = new PacketDescriptor(
                "play.clientbound.minecraft:block_entity_data",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                6,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "test/block_entity_data",
                PacketDescriptor.PlaybackScope.SAFE);
        BlockPos position = new BlockPos(33_554_431, 2_047, -33_554_432);

        var context = captureContext(
                new ClientboundBlockEntityDataPacket(
                        position, BlockEntityTypes.CHEST, new CompoundTag()),
                descriptor,
                Key.key("minecraft:overworld"));

        assertEquals(
                Optional.of(new BlockPosition(33_554_431, 2_047, -33_554_432)),
                context.position());
    }

    @Test
    void extractsTheExactBlockDestructionPositionForSpatialCapture() {
        PacketDescriptor descriptor = new PacketDescriptor(
                "play.clientbound.minecraft:block_destruction",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                5,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "test/block_destruction",
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
        BlockPos position = new BlockPos(-33_554_432, -2_048, 33_554_431);

        var context = captureContext(
                new ClientboundBlockDestructionPacket(41, position, 9),
                descriptor,
                Key.key("minecraft:overworld"));

        assertEquals(Optional.of(new BlockPosition(-33_554_432, -2_048, 33_554_431)), context.position());
    }

    private static dev.voldechse.replayframework.adapter.CaptureContext captureContext(
            Object packet, PacketDescriptor descriptor, Key world) {
        UUID connectionId = UUID.randomUUID();
        var state = new Paper26_2AuthoritativeStateIndex();
        state.world(connectionId, world.toString());
        ClientboundPacketDefinition<?> definition = Paper26_2ClientboundPacketCatalog.catalog()
                .require(descriptor.typeName());
        return definition.captureContext(packet, descriptor, connectionId, state, 0L);
    }

    @Test
    void copiesTheFinalPacketEventsBufferIncludingARewrittenPacketId() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeByte(99);
            buffer.writeByte(0xAC);
            buffer.writeByte(0x02);
            buffer.writeBytes(new byte[] {4, 5, 6});
            buffer.readerIndex(1);

            Paper26_2CaptureBridge.ObservedWirePacket packet =
                    Paper26_2CaptureBridge.copyWirePacket(buffer);

            assertEquals(300, packet.packetId());
            assertArrayEquals(new byte[] {4, 5, 6}, packet.payload());
            assertEquals(1, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }
}

