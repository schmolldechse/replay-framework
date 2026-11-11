package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import org.junit.jupiter.api.Test;

class Paper26_3CaptureBridgeTest {

    @Test
    void extractsTheExactBlockDestructionPositionForSpatialCapture() {
        PacketDescriptor descriptor = new PacketDescriptor(
                "minecraft:block_destruction",
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

        var context = new PaperConnectionAccessor().captureContext(
                new ClientboundBlockDestructionPacket(41, position, 9),
                descriptor,
                Optional.of(Key.key("minecraft:overworld")));

        assertEquals(Optional.of(new BlockPosition(-33_554_432, -2_048, 33_554_431)), context.position());
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

            Paper26_3CaptureBridge.ObservedWirePacket packet =
                    Paper26_3CaptureBridge.copyWirePacket(buffer);

            assertEquals(300, packet.packetId());
            assertArrayEquals(new byte[] {4, 5, 6}, packet.payload());
            assertEquals(1, buffer.readerIndex());
        } finally {
            buffer.release();
        }
    }
}
