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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id077RemoveEntitiesPacketDefinitionTest {

    private static final int PACKET_ID = 77;
    private static final String TYPE_NAME = "play.clientbound.minecraft:remove_entities";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesEntityRemovalAsCheckpointRelevantIdentityBearingSceneState() {
        var definition = new Id077RemoveEntitiesPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x4D, Id077RemoveEntitiesPacketDefinition.PACKET_ID);
        assertEquals("minecraft:remove_entities", Id077RemoveEntitiesPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInEntityDomainCatalogAndPaperRegistry() {
        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id077RemoveEntitiesPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id077RemoveEntitiesPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void mapsEveryTargetIdAndPreservesListOrderDuplicatesAndUnusualValues() {
        var definition = new Id077RemoveEntitiesPacketDefinition();
        var context = rewriter();
        context.trackEntityType(7, EntityTypes.FROG);
        int[] recordedIds = {
            Integer.MIN_VALUE, -1, 7, 7, 128, Integer.MAX_VALUE
        };
        var original = packet(recordedIds);

        var value = definition.read(original);
        assertEquals(boxed(recordedIds), value.entityIds());

        List<Integer> expectedIds = Arrays.stream(recordedIds)
                .map(context::mapEntityId)
                .boxed()
                .toList();
        var rewritten = definition.rewrite(value, context).value().orElseThrow();
        assertEquals(expectedIds, rewritten.entityIds());
        assertEquals(expectedIds, entityIds(definition.rebuild(rewritten)));
        assertEquals(expectedIds.get(2), expectedIds.get(3));
        assertTrue(context.recordedEntityType(7).isEmpty());
        for (int index = 0; index < recordedIds.length; index++) {
            assertNotEquals(recordedIds[index], expectedIds.get(index));
        }
    }

    @Test
    void preservesEmptyListsAndSnapshotsMutableInputValues() {
        var definition = new Id077RemoveEntitiesPacketDefinition();
        var context = rewriter();

        var empty = definition.read(packet());
        assertEquals(List.of(), empty.entityIds());
        assertEquals(List.of(), definition.rewrite(empty, context).value().orElseThrow().entityIds());
        assertEquals(List.of(), entityIds(definition.rebuild(empty)));

        ArrayList<Integer> mutableIds = new ArrayList<>(List.of(3, 5, 3));
        var snapshot = new Id077RemoveEntitiesPacketDefinition.Value(mutableIds);
        mutableIds.clear();
        assertEquals(List.of(3, 5, 3), snapshot.entityIds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entityIds().add(9));
        assertThrows(NullPointerException.class,
                () -> new Id077RemoveEntitiesPacketDefinition.Value(null));
        assertThrows(NullPointerException.class,
                () -> new Id077RemoveEntitiesPacketDefinition.Value(Arrays.asList(1, null)));
    }

    @Test
    void nativeProtocolUsesIdSeventySevenAndPreservesVarIntListBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        int[][] cases = {
            {},
            {0},
            {Integer.MIN_VALUE, -1, 0, 1, 127, 128, 255, 16_383, 16_384, Integer.MAX_VALUE},
            repeatedIds(127),
            repeatedIds(128),
            repeatedIds(255)
        };

        for (int[] expectedIds : cases) {
            ByteBuf encoded = encodePacket(protocol, packet(expectedIds));
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf wire = encoded.duplicate();
                assertEquals(PACKET_ID, readVarInt(wire));
                assertEquals(expectedIds.length, readVarInt(wire));
                for (int expectedId : expectedIds) {
                    assertEquals(expectedId, readVarInt(wire));
                }
                assertFalse(wire.isReadable());

                ClientboundRemoveEntitiesPacket decoded = assertInstanceOf(
                        ClientboundRemoveEntitiesPacket.class, decode(protocol, encoded));
                assertEquals(boxed(expectedIds), entityIds(decoded));
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
    void nativeDecoderTreatsNegativeListCountAsAnEmptyList() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, PACKET_ID);
            writeVarInt(input, -1);

            ClientboundRemoveEntitiesPacket decoded = assertInstanceOf(
                    ClientboundRemoveEntitiesPacket.class, decode(protocol, input));
            assertEquals(List.of(), entityIds(decoded));
            assertFalse(input.isReadable());

            ByteBuf normalized = encodePacket(protocol, decoded);
            try {
                ByteBuf wire = normalized.duplicate();
                assertEquals(PACKET_ID, readVarInt(wire));
                assertEquals(0, readVarInt(wire));
                assertFalse(wire.isReadable());
            } finally {
                normalized.release();
            }
        } finally {
            input.release();
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {1}); // one entity declared, no entity ID follows

        ByteBuf complete = encodePacket(protocol, packet(
                Integer.MIN_VALUE, -1, 0, 127, 128, 16_384, Integer.MAX_VALUE));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] payload = new byte[wire.readableBytes()];
            wire.readBytes(payload);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, Arrays.copyOf(payload, length));
            }
        } finally {
            complete.release();
        }
    }

    @Test
    void playbackRewritesTheRemovalTargetsAndEncodesTheRebuiltPacket() {
        var definition = new Id077RemoveEntitiesPacketDefinition();
        var context = rewriter();
        int[] recordedIds = {401, 402, 401};
        ClientboundRemoveEntitiesPacket original = packet(recordedIds);
        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundRemoveEntitiesPacket rewritten = assertInstanceOf(
                ClientboundRemoveEntitiesPacket.class, result.packet().orElseThrow());
        assertEquals(Arrays.stream(recordedIds).map(context::mapEntityId).boxed().toList(),
                entityIds(rewritten));
        assertEquals(PACKET_ID,
                definition.encode(rewritten, descriptor(), codec(rewritten)).packetId());
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncoding() {
        var definition = new Id077RemoveEntitiesPacketDefinition();
        ClientboundRemoveEntitiesPacket original = packet(402);

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID + 1, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(), descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE),
                codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                original,
                descriptor(PACKET_ID, false, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original)));
    }

    private static ClientboundRemoveEntitiesPacket packet(int... entityIds) {
        return new ClientboundRemoveEntitiesPacket(entityIds);
    }

    private static List<Integer> entityIds(ClientboundRemoveEntitiesPacket packet) {
        return packet.getEntityIds().intStream().boxed().toList();
    }

    private static List<Integer> boxed(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    private static int[] repeatedIds(int count) {
        int[] values = new int[count];
        Arrays.fill(values, 17);
        return values;
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
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor descriptor(
            int packetId, boolean replayable, PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundRemoveEntitiesPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundRemoveEntitiesPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, PACKET_ID);
            input.writeBytes(payload);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
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
        throw new IllegalArgumentException("VarInt exceeds five bytes");
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buffer.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
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
