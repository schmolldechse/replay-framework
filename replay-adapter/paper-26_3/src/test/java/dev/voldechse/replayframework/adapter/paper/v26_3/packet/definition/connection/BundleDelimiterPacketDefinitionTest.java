package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.connection;

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
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class BundleDelimiterPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:bundle_delimiter";

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void capturesDelimiterAsUnsafeTransportControl() {
        var definition = new BundleDelimiterPacketDefinition();

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
    void blocksBeforeThePlaybackCodecCanDecode() {
        var definition = new BundleDelimiterPacketDefinition();
        PacketDescriptor descriptor = new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                0,
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.UNSAFE);
        RawPacketFrame frame = new RawPacketFrame(
                0L, 0L, 0, PacketPhase.PLAY, 0, new byte[0]);
        AtomicBoolean decoded = new AtomicBoolean();
        PacketCodec<Object> codec = new PacketCodec<>() {
            @Override
            public Object decode(RawPacketFrame ignored) {
                decoded.set(true);
                throw new AssertionError("blocked delimiter must not be decoded");
            }

            @Override
            public EncodedPacket encode(Object ignored) {
                throw new AssertionError("blocked delimiter must not be encoded");
            }
        };

        assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame, descriptor, codec, null));
        assertFalse(decoded.get());
    }

    @Test
    void nativeProtocolEncodesOnlyPacketIdAndDecodesNoPayload() {
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
        ByteBuf encoded = Unpooled.buffer();
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, 0);
            Packet<?> decoded = decode(protocol, input);
            assertInstanceOf(ClientboundBundleDelimiterPacket.class, decoded);
            assertFalse(input.isReadable());

            encode(protocol, encoded, decoded);
            assertEquals(0, readVarInt(encoded));
            assertFalse(encoded.isReadable());

            ByteBuf secondInput = Unpooled.buffer();
            try {
                writeVarInt(secondInput, 0);
                assertSame(decoded, decode(protocol, secondInput));
            } finally {
                secondInput.release();
            }
        } finally {
            encoded.release();
            input.release();
        }
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
