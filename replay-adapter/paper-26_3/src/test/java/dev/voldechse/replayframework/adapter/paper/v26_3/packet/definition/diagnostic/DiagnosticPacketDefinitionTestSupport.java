package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.util.debug.DebugSubscription;

/** Shared assertions for this version's deliberately non-replayable diagnostics. */
final class DiagnosticPacketDefinitionTestSupport {

    private DiagnosticPacketDefinitionTestSupport() {}

    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    static void assertDefinition(
            PacketDefinition<?> definition,
            List<? extends PacketDefinition<?>> definitions,
            int expectedId,
            String identifier) {
        assertEquals(expectedId, definition.packetId());
        assertEquals("play.clientbound." + identifier, definition.typeName());
        assertEquals(PacketDisposition.CONTROL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertFalse(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.UNSAFE,
                definition.classification().playbackScope());
        assertEquals(
                1,
                definitions.stream()
                        .filter(candidate -> candidate.typeName().equals(definition.typeName()))
                        .count());
    }

    static void assertPlaybackBlockedBeforeCodec(PacketDefinition<?> definition) {
        AtomicInteger encodeCalls = new AtomicInteger();
        AtomicInteger decodeCalls = new AtomicInteger();
        PacketCodec<Object> codec = new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodeCalls.incrementAndGet();
                throw new AssertionError("blocked debug packet must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodeCalls.incrementAndGet();
                throw new AssertionError("blocked debug packet must not decode");
            }
        };

        byte[][] payloads = {
            new byte[0],
            new byte[] {0},
            new byte[] {0, 0},
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07},
            new byte[64]
        };
        for (byte[] payload : payloads) {
            IncompatibleAdapterException failure = assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, definition.packetId(), payload),
                            descriptor(definition),
                            codec,
                            null));
            assertEquals(
                    "packet descriptor is not permitted for playback: " + definition.typeName(),
                    failure.getMessage());
        }

        assertThrows(
                IncompatibleAdapterException.class,
                () -> definition.rewrite(new Object(), descriptor(definition), null));
        assertEquals(0, encodeCalls.get());
        assertEquals(0, decodeCalls.get());
    }

    static List<DebugSubscription<?>> subscriptions() {
        return BuiltInRegistries.DEBUG_SUBSCRIPTION.stream().toList();
    }

    static List<DebugSubscription<?>> valueSubscriptions() {
        return subscriptions().stream()
                .filter(subscription -> subscription.valueStreamCodec() != null)
                .toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static DebugSubscription.Update<?> emptyUpdate(DebugSubscription<?> subscription) {
        return ((DebugSubscription) subscription).emptyUpdate();
    }

    static Packet<?> roundTrip(Packet<?> packet) {
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
        ByteBuf input = Unpooled.buffer();
        try {
            encode(protocol, input, packet);
            Packet<?> decoded = decode(protocol, input);
            assertFalse(input.isReadable());
            return decoded;
        } finally {
            input.release();
        }
    }

    static byte[] encodeToBytes(Packet<?> packet) {
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
        ByteBuf output = Unpooled.buffer();
        try {
            encode(protocol, output, packet);
            byte[] bytes = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), bytes);
            return bytes;
        } finally {
            output.release();
        }
    }

    static int readVarInt(ByteBuf input) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int next = input.readUnsignedByte();
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt is longer than five bytes");
    }

    private static PacketDescriptor descriptor(PacketDefinition<?> definition) {
        return new PacketDescriptor(
                definition.typeName(),
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                definition.packetId(),
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                "paper-26.3/" + definition.typeName(),
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf output, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(output, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf input) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(input);
    }
}
