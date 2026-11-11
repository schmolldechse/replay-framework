package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id004BlockChangedAckPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:block_changed_ack";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAcknowledgementAsUnsafeConnectionControl() {
        var definition = new Id004BlockChangedAckPacketDefinition();

        assertEquals(4, definition.packetId());
        assertEquals(0x04, Id004BlockChangedAckPacketDefinition.PACKET_ID);
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
        var definition = new Id004BlockChangedAckPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07},
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}
        }) {
            IncompatibleAdapterException failure = assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(payload), descriptor(), codec, rewriter()));

            assertEquals(
                    "packet descriptor is not permitted for playback: " + TYPE_NAME,
                    failure.getMessage());
        }

        assertEquals(0, decodes.get());
    }

    @Test
    void blocksAlreadyDecodedValuesWithoutInspectingTheirType() {
        var definition = new Id004BlockChangedAckPacketDefinition();

        IncompatibleAdapterException failure = assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(new Object(), descriptor(), rewriter()));

        assertEquals(
                "packet descriptor is not permitted for playback: " + TYPE_NAME,
                failure.getMessage());
    }

    @Test
    void nativeProtocolUsesIdFourAndRoundTripsEveryIntShape() {
        ProtocolInfo<?> protocol = protocol();

        for (int sequence : new int[] {
            0,
            1,
            127,
            128,
            16383,
            16384,
            Integer.MAX_VALUE,
            -1,
            Integer.MIN_VALUE
        }) {
            assertNativeRoundTrip(protocol, sequence);
        }
    }

    @Test
    void nativeProtocolRejectsMissingAndOversizedSequenceVarInts() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}
        }) {
            ByteBuf encoded = Unpooled.buffer();
            try {
                writeVarInt(encoded, 4);
                encoded.writeBytes(payload);
                assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
            } finally {
                encoded.release();
            }
        }
    }

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, int expectedSequence) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundBlockChangedAckPacket(expectedSequence));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(4, readVarInt(wireView));
            assertEquals(expectedSequence, readVarInt(wireView));
            assertFalse(wireView.isReadable());

            ClientboundBlockChangedAckPacket decoded = assertInstanceOf(
                    ClientboundBlockChangedAckPacket.class, decode(protocol, encoded));
            assertEquals(expectedSequence, decoded.sequence());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                4,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 4, payload);
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("blocked block_changed_ack must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("blocked block_changed_ack must not decode");
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
