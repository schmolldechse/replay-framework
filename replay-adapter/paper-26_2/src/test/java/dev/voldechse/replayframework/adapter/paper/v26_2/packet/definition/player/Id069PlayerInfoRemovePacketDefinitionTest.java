package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id069PlayerInfoRemovePacketDefinitionTest {

    private static final int PACKET_ID = 0x45;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_info_remove";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RECORDED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SECOND_RECORDED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID THIRD_RECORDED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final UUID MAX_UUID = new UUID(-1L, -1L);

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersProfileRemovalAsIdentityBearingCheckpointState() {
        var definition = new Id069PlayerInfoRemovePacketDefinition();

        assertEquals(69, definition.packetId());
        assertEquals(PACKET_ID, Id069PlayerInfoRemovePacketDefinition.PACKET_ID);
        assertEquals("minecraft:player_info_remove", Id069PlayerInfoRemovePacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());

        assertEquals(1, PlayerPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
        assertEquals(1, Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void readsRewritesAndRebuildsEveryProfileIdWithoutChangingListSemantics() {
        var definition = new Id069PlayerInfoRemovePacketDefinition();
        Paper26_2ReplayPacketRewriter rewriter = rewriter();
        List<UUID> sourceIds = List.of(
                RECORDED_ID, SECOND_RECORDED_ID, RECORDED_ID, ZERO_UUID, MAX_UUID);
        ClientboundPlayerInfoRemovePacket source = packet(sourceIds);

        var value = definition.read(source);
        assertEquals(sourceIds, value.profileIds());
        var rewriteResult = definition.rewrite(value, rewriter);
        assertEquals(
                dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult.Decision.SEND,
                rewriteResult.decision());
        var rewrittenValue = rewriteResult.value().orElseThrow();
        List<UUID> expectedIds = sourceIds.stream().map(rewriter::mapUuid).toList();
        assertEquals(expectedIds, rewrittenValue.profileIds());
        assertEquals(expectedIds.getFirst(), expectedIds.get(2));
        assertEquals(sourceIds.size(), rewrittenValue.profileIds().size());
        assertEquals(
                sourceIds.size() - 1,
                new java.util.HashSet<>(rewrittenValue.profileIds()).size());

        for (UUID rewrittenId : rewrittenValue.profileIds()) {
            assertNotEquals(OBSERVER_ID, rewrittenId);
            assertNotEquals(SESSION_ID, rewrittenId);
            assertNotEquals(RECORDED_ID, rewrittenId);
            assertNotEquals(SECOND_RECORDED_ID, rewrittenId);
            assertNotEquals(THIRD_RECORDED_ID, rewrittenId);
        }

        ClientboundPlayerInfoRemovePacket rebuilt = definition.rebuild(rewrittenValue);
        assertEquals(expectedIds, rebuilt.profileIds());
        assertEquals(List.of(), definition.rebuild(definition.rewrite(
                definition.read(packet(List.of())), rewriter).value().orElseThrow()).profileIds());
    }

    @Test
    void retriesUuidMappingWhenTheFirstReplayCandidateIsReserved() {
        UUID firstCandidate = UUID.nameUUIDFromBytes(
                (SESSION_ID + ":uuid:" + RECORDED_ID).getBytes(StandardCharsets.UTF_8));
        Paper26_2ReplayPacketRewriter rewriter = rewriter(Set.of(
                OBSERVER_ID,
                SESSION_ID,
                RECORDED_ID,
                firstCandidate));

        UUID mapped = rewriter.mapUuid(RECORDED_ID);

        assertNotEquals(firstCandidate, mapped);
        assertNotEquals(OBSERVER_ID, mapped);
        assertNotEquals(RECORDED_ID, mapped);
        assertEquals(mapped, rewriter.mapUuid(RECORDED_ID));
    }

    @Test
    void valueDefensivelyCopiesItsProfileListAndRejectsNullIds() {
        List<UUID> mutableSource = new ArrayList<>(List.of(RECORDED_ID));
        var value = new Id069PlayerInfoRemovePacketDefinition.Value(mutableSource);
        mutableSource.add(SECOND_RECORDED_ID);

        assertEquals(List.of(RECORDED_ID), value.profileIds());
        assertThrows(UnsupportedOperationException.class,
                () -> value.profileIds().add(SECOND_RECORDED_ID));
        assertThrows(NullPointerException.class,
                () -> new Id069PlayerInfoRemovePacketDefinition.Value(null));
        assertThrows(NullPointerException.class,
                () -> new Id069PlayerInfoRemovePacketDefinition.Value(
                        java.util.Arrays.asList(RECORDED_ID, null)));
    }

    @Test
    void handlesPlaybackWithTheNativeCodecAndEncodesTheRebuiltPacket() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id069PlayerInfoRemovePacketDefinition();
        Paper26_2ReplayPacketRewriter rewriter = rewriter();
        ClientboundPlayerInfoRemovePacket source = packet(
                List.of(RECORDED_ID, SECOND_RECORDED_ID, RECORDED_ID));
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = nativeCodec(protocol, decodes, encodes);

        PacketRewriteResult result = definition.handlePlayback(
                frame(nativePayload(protocol, source)), descriptor(), codec, rewriter);
        ClientboundPlayerInfoRemovePacket rebuilt = assertInstanceOf(
                ClientboundPlayerInfoRemovePacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(List.of(
                rewriter.mapUuid(RECORDED_ID),
                rewriter.mapUuid(SECOND_RECORDED_ID),
                rewriter.mapUuid(RECORDED_ID)), rebuilt.profileIds());
        assertEquals(1, decodes.get());
        assertEquals(0, encodes.get());

        var encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        assertArrayEquals(nativePayload(protocol, rebuilt), encoded.payload());
        assertEquals(1, encodes.get());
    }

    @Test
    void rejectsDescriptorAndDecodedTypeMismatches() {
        var definition = new Id069PlayerInfoRemovePacketDefinition();
        PacketCodec<Object> codec = nativeCodec(
                protocol(), new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
            assertThrows(IncompatibleAdapterException.class, () ->
                    definition.rewrite(new Object(), mismatch, rewriter()));
        }
    }

    @Test
    void nativeCodecUsesTheExactPacketIdAndPreservesEveryListEntryInOrder() {
        ProtocolInfo<?> protocol = protocol();
        List<List<UUID>> cases = List.of(
                List.of(),
                List.of(ZERO_UUID),
                List.of(MAX_UUID, RECORDED_ID, MAX_UUID),
                ids(127),
                ids(128));

        for (List<UUID> expectedIds : cases) {
            ClientboundPlayerInfoRemovePacket expected = packet(expectedIds);
            ByteBuf encoded = encodePacket(protocol, expected);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf wireView = encoded.duplicate();
                assertEquals(PACKET_ID, VarInt.read(wireView));
                assertEquals(expectedIds.size(), VarInt.read(wireView));
                for (UUID expectedId : expectedIds) {
                    assertEquals(expectedId, FriendlyByteBuf.readUUID(wireView));
                }
                assertFalse(wireView.isReadable());

                ClientboundPlayerInfoRemovePacket decoded = assertInstanceOf(
                        ClientboundPlayerInfoRemovePacket.class, decode(protocol, encoded));
                assertEquals(expectedIds, decoded.profileIds());
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
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedListBodyPrefixAndInvalidLengths() {
        ProtocolInfo<?> protocol = protocol();
        byte[] body = nativePayload(protocol, packet(List.of(RECORDED_ID, SECOND_RECORDED_ID)));

        for (int length = 0; length < body.length; length++) {
            ByteBuf truncated = Unpooled.buffer();
            try {
                VarInt.write(truncated, PACKET_ID);
                truncated.writeBytes(body, 0, length);
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }

        assertNativeDecodeFails(protocol, buffer -> VarInt.write(buffer, -1));
        assertNativeDecodeFails(protocol, buffer -> VarInt.write(buffer, 2));
        assertNativeDecodeFails(protocol, buffer -> buffer.writeByte(0x80));
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerInfoRemovePacket expected = packet(List.of(RECORDED_ID));
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            encoded.writeByte(0x7A);
            ClientboundPlayerInfoRemovePacket decoded = assertInstanceOf(
                    ClientboundPlayerInfoRemovePacket.class, decode(protocol, encoded));
            assertEquals(expected.profileIds(), decoded.profileIds());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static List<UUID> ids(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> new UUID(0x1020304050607080L, index))
                .toList();
    }

    private static ClientboundPlayerInfoRemovePacket packet(List<UUID> profileIds) {
        return new ClientboundPlayerInfoRemovePacket(profileIds);
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            java.util.function.Consumer<ByteBuf> bodyWriter) {
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
            bodyWriter.accept(input);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundPlayerInfoRemovePacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
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

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return rewriter(Set.of(
                OBSERVER_ID,
                SESSION_ID,
                RECORDED_ID,
                SECOND_RECORDED_ID,
                THIRD_RECORDED_ID));
    }

    private static Paper26_2ReplayPacketRewriter rewriter(Set<UUID> reservedUuids) {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID), reservedUuids, Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
                replayable,
                direction == PacketDescriptor.Direction.CLIENTBOUND && replayable,
                "paper-26.2/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> nativeCodec(
            ProtocolInfo<?> protocol,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                if (!(packet instanceof Packet<?> nativePacket)) {
                    throw new AssertionError("native packet codec received a non-packet value");
                }
                ByteBuf wire = encodePacket(protocol, nativePacket);
                try {
                    int packetId = VarInt.read(wire);
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            packetId,
                            bytes(wire));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                ByteBuf wire = Unpooled.buffer();
                try {
                    VarInt.write(wire, frame.packetId());
                    wire.writeBytes(frame.payload());
                    return Id069PlayerInfoRemovePacketDefinitionTest.decode(protocol, wire);
                } finally {
                    wire.release();
                }
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
