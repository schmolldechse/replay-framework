package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id107SetPassengersPacketDefinitionTest {

    private static final int PACKET_ID = 107;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_passengers";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000171");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000172");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesPassengerRelationshipsAsCheckpointRelevantEntityState() {
        var definition = new Id107SetPassengersPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x6B, Id107SetPassengersPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_passengers", Id107SetPassengersPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.PASSENGERS,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id107SetPassengersPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id107SetPassengersPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void nativeCodecPreservesTheVehicleThenOrderedPassengerArrayForAllShapes() {
        ProtocolInfo<?> protocol = protocol();
        List<int[]> passengerLists = List.of(
                new int[0],
                new int[] {17},
                new int[] {31, 23, 47},
                new int[] {52, 52, 0},
                new int[] {Integer.MIN_VALUE, -1, Integer.MAX_VALUE});

        for (int[] passengers : passengerLists) {
            assertNativeRoundTrip(protocol, packet(113, passengers));
        }
    }

    @Test
    void nativeWireOrderIsPacketIdVehicleVarIntThenLengthPrefixedPassengerVarInts() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetPassengersPacket expected = packet(0x1234, 71, 83, 97);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(0x1234, fields.readVarInt());
            assertArrayEquals(new int[] {71, 83, 97}, fields.readVarIntArray());
            assertFalse(fields.isReadable());
            assertEquals(GamePacketTypes.CLIENTBOUND_SET_PASSENGERS, expected.type());

            ClientboundSetPassengersPacket decoded = assertInstanceOf(
                    ClientboundSetPassengersPacket.class, decode(protocol, encoded));
            assertEquals(0x1234, decoded.getVehicle());
            assertArrayEquals(new int[] {71, 83, 97}, decoded.getPassengers());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void rewriteMapsTheVehicleAndEveryPassengerWithoutChangingOrderOrDuplicates() {
        var definition = new Id107SetPassengersPacketDefinition();
        var context = rewriter();
        int vehicleId = 1301;
        int[] passengerIds = {1302, 1303, 1302, 0};

        var rewritten = definition.rewrite(
                packet(vehicleId, passengerIds),
                descriptor(),
                context).packet().orElseThrow();
        ClientboundSetPassengersPacket packet = assertInstanceOf(
                ClientboundSetPassengersPacket.class, rewritten);

        assertEquals(context.mapEntityId(vehicleId), packet.getVehicle());
        assertNotEquals(vehicleId, packet.getVehicle());
        assertArrayEquals(Arrays.stream(passengerIds)
                .map(context::mapEntityId)
                .toArray(), packet.getPassengers());
        assertEquals(packet.getPassengers()[0], packet.getPassengers()[2]);
        assertNotEquals(passengerIds[1], packet.getPassengers()[1]);
    }

    @Test
    void rewritePreservesAnEmptyListAsTheVehiclePassengerClearOperation() {
        var definition = new Id107SetPassengersPacketDefinition();
        var context = rewriter();
        int vehicleId = 1310;

        var rewritten = definition.rewrite(
                packet(vehicleId),
                descriptor(),
                context).packet().orElseThrow();
        ClientboundSetPassengersPacket packet = assertInstanceOf(
                ClientboundSetPassengersPacket.class, rewritten);

        assertEquals(context.mapEntityId(vehicleId), packet.getVehicle());
        assertArrayEquals(new int[0], packet.getPassengers());
    }

    @Test
    void valueAndReadModelDefensivelyCopyTheNativeMutablePassengerArray() {
        var definition = new Id107SetPassengersPacketDefinition();
        int[] source = {17, 18};
        var value = new Id107SetPassengersPacketDefinition.Value(16, source);
        source[0] = 99;
        assertArrayEquals(new int[] {17, 18}, value.passengerIds());

        int[] exposed = value.passengerIds();
        exposed[1] = 100;
        assertArrayEquals(new int[] {17, 18}, value.passengerIds());

        ClientboundSetPassengersPacket nativePacket = packet(19, 20, 21);
        var snapshot = definition.read(nativePacket);
        nativePacket.getPassengers()[0] = 101;
        assertArrayEquals(new int[] {20, 21}, snapshot.passengerIds());

        assertThrows(NullPointerException.class,
                () -> new Id107SetPassengersPacketDefinition.Value(1, null));
    }

    @Test
    void playbackDecodesRewritesRebuildsAndEncodesEveryEntityReference() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id107SetPassengersPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = codec(protocol);
        int vehicleId = 1401;
        int[] passengers = {1402, 1403, 1402};

        PacketRewriteResult result = definition.handlePlayback(
                frame(body(protocol, packet(vehicleId, passengers))),
                descriptor(),
                codec,
                context);
        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundSetPassengersPacket rebuilt = assertInstanceOf(
                ClientboundSetPassengersPacket.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(vehicleId), rebuilt.getVehicle());
        int[] rewrittenPassengers = Arrays.stream(passengers)
                .map(context::mapEntityId)
                .toArray();
        assertArrayEquals(rewrittenPassengers, rebuilt.getPassengers());

        PacketCodec.EncodedPacket encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PacketPhase.PLAY, encoded.phase());
        assertEquals(PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
        assertEquals(PACKET_ID, encoded.packetId());
        ClientboundSetPassengersPacket decodedOutput = assertInstanceOf(
                ClientboundSetPassengersPacket.class,
                decodePayload(protocol, encoded.payload()));
        assertEquals(context.mapEntityId(vehicleId), decodedOutput.getVehicle());
        assertArrayEquals(rewrittenPassengers, decodedOutput.getPassengers());
    }

    @Test
    void playbackRewritesVehicleAndKeepsAnEmptyPassengerArrayEmpty() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id107SetPassengersPacketDefinition();
        var context = rewriter();
        PacketRewriteResult result = definition.handlePlayback(
                frame(body(protocol, packet(1410))), descriptor(), codec(protocol), context);

        ClientboundSetPassengersPacket rebuilt = assertInstanceOf(
                ClientboundSetPassengersPacket.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(1410), rebuilt.getVehicle());
        assertArrayEquals(new int[0], rebuilt.getPassengers());
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedPrefixMalformedVarIntAndInvalidArrayLength() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(300, 400, 500));
        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, Arrays.copyOf(completeBody, length));
        }

        assertNativeDecodeFails(protocol, new byte[] {(byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {0, (byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        });
        assertNativeDecodeFails(protocol, new byte[] {0, 2});
        assertNativeDecodeFails(protocol, new byte[] {0, 2, 1});

        ByteBuf negativeLength = Unpooled.buffer();
        try {
            VarInt.write(negativeLength, 0);
            VarInt.write(negativeLength, -1);
            assertNativeDecodeFails(protocol, bytes(negativeLength));
        } finally {
            negativeLength.release();
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(protocol, packet(1510, 1511, 1512));
        try {
            wire.writeByte(0x7A);
            ClientboundSetPassengersPacket decoded = assertInstanceOf(
                    ClientboundSetPassengersPacket.class, decode(protocol, wire));

            assertEquals(1510, decoded.getVehicle());
            assertArrayEquals(new int[] {1511, 1512}, decoded.getPassengers());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void validatesDescriptorBeforeDecodingOrEncoding() {
        var definition = new Id107SetPassengersPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ClientboundSetPassengersPacket packet = packet(2, 3);

        List<PacketDescriptor> mismatches = List.of(
                descriptor(TYPE_NAME, PACKET_ID + 1, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor("play.clientbound.minecraft:other", PACKET_ID, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID, false,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
            assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                    packet, mismatch, codec));
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetPassengersPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(expected.getVehicle(), fields.readVarInt());
            assertArrayEquals(expected.getPassengers(), fields.readVarIntArray());
            assertFalse(fields.isReadable());

            ClientboundSetPassengersPacket decoded = assertInstanceOf(
                    ClientboundSetPassengersPacket.class, decode(protocol, encoded));
            assertEquals(expected.getVehicle(), decoded.getVehicle());
            assertArrayEquals(expected.getPassengers(), decoded.getPassengers());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static ClientboundSetPassengersPacket packet(int vehicleId, int... passengerIds) {
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        try {
            body.writeVarInt(vehicleId);
            body.writeVarIntArray(passengerIds);
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(body);
        } finally {
            body.release();
        }
    }

    private static byte[] body(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            return bytes(fields);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            encode(protocol, buffer, packet);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.release();
            throw failure;
        }
    }

    private static Object decodePayload(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(payload);
            return decode(protocol, wire);
        } finally {
            wire.release();
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, true,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetPassengersPacket nativePacket = assertInstanceOf(
                        ClientboundSetPassengersPacket.class, packet);
                ByteBuf wire = encodePacket(protocol, nativePacket);
                try {
                    FriendlyByteBuf fields = new FriendlyByteBuf(wire.duplicate());
                    assertEquals(PACKET_ID, fields.readVarInt());
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(fields));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                return decodePayload(protocol, frame.payload());
            }
        };
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("descriptor mismatch must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("descriptor mismatch must not decode");
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
}
