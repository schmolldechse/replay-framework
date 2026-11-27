package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.connection;

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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id130TickingStatePacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:ticking_state";

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesTickingUpdateAsCapturedUnsafeControl() {
        var definition = new Id130TickingStatePacketDefinition();

        assertEquals(130, definition.packetId());
        assertEquals(0x82, Id130TickingStatePacketDefinition.PACKET_ID);
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
        var definition = new Id130TickingStatePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {0x00, 0x00, (byte) 0xA0, 0x41, 1},
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
        var definition = new Id130TickingStatePacketDefinition();

        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(new ClientboundTickingStatePacket(20.0F, true), descriptor(), null));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void nativeProtocolUsesId127AndRoundTripsAllTickingStateFields() {
        ProtocolInfo<?> protocol = protocol();
        int[] tickRateBits = {
            0x00000000, // positive zero
            0x80000000, // negative zero
            0x00000001, // smallest positive subnormal
            0x3F800000, // 1.0
            0x41A00000, // 20.0
            0x7F7FFFFF, // largest finite positive value
            0xFF7FFFFF, // largest finite negative value
            0x7F800000, // positive infinity
            0xFF800000, // negative infinity
            0x7FC00000 // NaN
        };

        for (int rateBits : tickRateBits) {
            for (boolean frozen : new boolean[] {false, true}) {
                assertNativeRoundTrip(protocol, rateBits, frozen);
            }
        }
    }

    @Test
    void nativeProtocolRejectsMissingAndTruncatedFields() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {0, 0},
            new byte[] {0, 0, 0},
            new byte[] {0, 0, 0, 0}
        }) {
            ByteBuf encoded = Unpooled.buffer();
            try {
                writeVarInt(encoded, Id130TickingStatePacketDefinition.PACKET_ID);
                encoded.writeBytes(payload);
                assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
            } finally {
                encoded.release();
            }
        }
    }

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, int expectedRateBits, boolean expectedFrozen) {
        float expectedRate = Float.intBitsToFloat(expectedRateBits);
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundTickingStatePacket(expectedRate, expectedFrozen));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(Id130TickingStatePacketDefinition.PACKET_ID, readVarInt(wireView));
            assertEquals(expectedRateBits, wireView.readInt());
            assertEquals(expectedFrozen, wireView.readBoolean());
            assertFalse(wireView.isReadable());

            ClientboundTickingStatePacket decoded = assertInstanceOf(
                    ClientboundTickingStatePacket.class, decode(protocol, encoded));
            assertEquals(expectedRateBits, Float.floatToRawIntBits(decoded.tickRate()));
            assertEquals(expectedFrozen, decoded.isFrozen());
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
                Id130TickingStatePacketDefinition.PACKET_ID,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(
                0L,
                0L,
                0,
                PacketPhase.PLAY,
                Id130TickingStatePacketDefinition.PACKET_ID,
                payload);
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("blocked ticking_state must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked ticking_state must not decode");
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
