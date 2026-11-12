package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id012ChunkBatchStartPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:chunk_batch_start";

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesStartMarkerAsUnsafeConnectionControl() {
        var definition = new Id012ChunkBatchStartPacketDefinition();

        assertEquals(12, definition.packetId());
        assertEquals(0x0C, Id012ChunkBatchStartPacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONTROL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertFalse(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.UNSAFE,
                definition.classification().playbackScope());
    }

    @Test
    void blocksEveryPayloadBeforePlaybackDecode() {
        var definition = new Id012ChunkBatchStartPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {(byte) 0xFF},
            new byte[] {(byte) 0x80, (byte) 0x80}
        }) {
            IncompatibleAdapterException failure = assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(payload), descriptor(), codec, null));

            assertEquals(
                    "packet descriptor is not permitted for playback: " + TYPE_NAME,
                    failure.getMessage());
        }

        assertEquals(0, decodes.get());
    }

    @Test
    void blocksAlreadyDecodedValuesWithoutInspectingTheirType() {
        var definition = new Id012ChunkBatchStartPacketDefinition();

        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(new Object(), descriptor(), null));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void nativeProtocolUsesIdTwelveAndTheCanonicalPayloadFreeSingleton() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, ClientboundChunkBatchStartPacket.INSTANCE);
            assertEquals(12, readVarInt(encoded));
            assertFalse(encoded.isReadable());

            ByteBuf firstInput = Unpooled.buffer();
            ByteBuf secondInput = Unpooled.buffer();
            try {
                writeVarInt(firstInput, 12);
                writeVarInt(secondInput, 12);
                Packet<?> first = decode(protocol, firstInput);
                Packet<?> second = decode(protocol, secondInput);

                assertInstanceOf(ClientboundChunkBatchStartPacket.class, first);
                assertSame(ClientboundChunkBatchStartPacket.INSTANCE, first);
                assertSame(first, second);
                assertFalse(firstInput.isReadable());
                assertFalse(secondInput.isReadable());
            } finally {
                firstInput.release();
                secondInput.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                12,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 12, payload);
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("blocked chunk_batch_start must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked chunk_batch_start must not decode");
            }
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int current = buffer.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new AssertionError("VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }
}
