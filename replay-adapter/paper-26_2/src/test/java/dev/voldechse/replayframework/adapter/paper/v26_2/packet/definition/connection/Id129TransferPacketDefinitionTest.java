package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id129TransferPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:transfer";

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesTransferAsCapturedUnsafeConnectionControl() {
        var definition = new Id129TransferPacketDefinition();

        assertEquals(129, definition.packetId());
        assertEquals(0x81, Id129TransferPacketDefinition.PACKET_ID);
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
        var definition = new Id129TransferPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {1, (byte) 'x'},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07}
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
        var definition = new Id129TransferPacketDefinition();

        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(
                        new ClientboundTransferPacket("replay.example", 25565), descriptor(), null));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void nativeProtocolUsesId129AndRoundTripsHostAndPortShapes() {
        ProtocolInfo<?> protocol = protocol();
        String longestHost = "a".repeat(32767);
        String[] hosts = {
            "",
            "localhost",
            "play.example.org",
            "192.0.2.42",
            "2001:db8::42",
            "münchen.example",
            longestHost
        };
        int[] ports = {
            0,
            1,
            80,
            25565,
            65535,
            Integer.MAX_VALUE,
            -1,
            Integer.MIN_VALUE
        };

        for (String host : hosts) {
            for (int port : ports) {
                assertNativeRoundTrip(protocol, host, port);
            }
        }
    }

    @Test
    void nativeProtocolRejectsTruncatedOrOverlongHostAndPortFields() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            new byte[] {1},
            new byte[] {0},
            new byte[] {0, (byte) 0x80},
            new byte[] {0, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}
        }) {
            ByteBuf encoded = Unpooled.buffer();
            try {
                writeVarInt(encoded, Id129TransferPacketDefinition.PACKET_ID);
                encoded.writeBytes(payload);
                assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
            } finally {
                encoded.release();
            }
        }

        ByteBuf oversizedHost = Unpooled.buffer();
        try {
            writeVarInt(oversizedHost, Id129TransferPacketDefinition.PACKET_ID);
            writeVarInt(oversizedHost, 32768);
            assertThrows(RuntimeException.class, () -> decode(protocol, oversizedHost));
        } finally {
            oversizedHost.release();
        }
    }

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, String expectedHost, int expectedPort) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundTransferPacket(expectedHost, expectedPort));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(Id129TransferPacketDefinition.PACKET_ID, readVarInt(wireView));
            byte[] hostBytes = expectedHost.getBytes(StandardCharsets.UTF_8);
            assertEquals(hostBytes.length, readVarInt(wireView));
            byte[] actualHostBytes = new byte[hostBytes.length];
            wireView.readBytes(actualHostBytes);
            assertEquals(expectedHost, new String(actualHostBytes, StandardCharsets.UTF_8));
            assertEquals(expectedPort, readVarInt(wireView));
            assertFalse(wireView.isReadable());

            ClientboundTransferPacket decoded = assertInstanceOf(
                    ClientboundTransferPacket.class, decode(protocol, encoded));
            assertEquals(expectedHost, decoded.host());
            assertEquals(expectedPort, decoded.port());
            assertFalse(encoded.isReadable());
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
                Id129TransferPacketDefinition.PACKET_ID,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(
                0L,
                0L,
                0,
                PacketPhase.PLAY,
                Id129TransferPacketDefinition.PACKET_ID,
                payload);
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("blocked transfer must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked transfer must not decode");
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
