package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id134UpdateTagsPacketDefinitionTest {

    private static final int PACKET_ID = 134;
    private static final String TYPE_NAME = "play.clientbound.minecraft:update_tags";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000134");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000135");
    private static final Identifier ITEM_TAG_ID = Identifier.parse("replay_test:tracked_items");
    private static final Identifier EMPTY_ITEM_TAG_ID = Identifier.parse("replay_test:empty_items");
    private static final int[] WIRE_ID_BOUNDARIES = {
        Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_384, Integer.MAX_VALUE
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersRegistryTagsAsSafeCheckpointGlobalState() {
        var definition = new Id134UpdateTagsPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x86, Id134UpdateTagsPacketDefinition.PACKET_ID);
        assertEquals("minecraft:update_tags", Id134UpdateTagsPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, definition.classification().playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        assertEquals(1, WorldPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
        assertEquals(1, Paper26_2ClientboundPacketCatalog.catalog().definitions().stream()
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
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_2ClientboundPacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeCodecRoundTripsEmptyAndPopulatedRegistryTagMaps() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundUpdateTagsPacket empty = assertNativeRoundTrip(
                protocol, new ClientboundUpdateTagsPacket(Map.of()));
        assertTrue(empty.getTags().isEmpty());

        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags =
                populatedRegistryTags();
        ClientboundUpdateTagsPacket decoded = assertNativeRoundTrip(
                protocol, new ClientboundUpdateTagsPacket(tags));

        assertEquals(tags.keySet(), decoded.getTags().keySet());
        for (var registryTags : tags.entrySet()) {
            var decodedPayload = decoded.getTags().get(registryTags.getKey());
            assertTrue(decodedPayload != null);
            assertEquals(registryTags.getValue().isEmpty(), decodedPayload.isEmpty());
            assertEquals(registryTags.getValue().size(), decodedPayload.size());
            assertArrayEquals(
                    encodeNetworkPayload(registryTags.getValue()),
                    encodeNetworkPayload(decodedPayload),
                    registryTags.getKey().toString());
        }

        var customPayload = decoded.getTags().get(customRegistryKey());
        assertEquals(1, customPayload.size());
        assertFalse(customPayload.isEmpty());
        assertTrue(decoded.getTags().get(Registries.TRIM_PATTERN).isEmpty());
    }

    @Test
    void payloadResolutionUsesRegistryIdsAndIgnoresMissingEntryIds() {
        TagNetworkSerialization.NetworkPayload payload = populatedRegistryTags()
                .get(Registries.ITEM);
        TagLoader.LoadResult<net.minecraft.world.item.Item> resolved =
                payload.resolve(BuiltInRegistries.ITEM);

        assertEquals(Registries.ITEM, resolved.key());
        TagKey<net.minecraft.world.item.Item> tagKey = TagKey.create(Registries.ITEM, ITEM_TAG_ID);
        assertEquals(
                java.util.List.of(
                        Items.STONE.builtInRegistryHolder(),
                        Items.DIRT.builtInRegistryHolder()),
                resolved.tags().get(tagKey));
        assertFalse(resolved.tags().containsKey(
                TagKey.create(Registries.ITEM, EMPTY_ITEM_TAG_ID)));

        TagNetworkSerialization.NetworkPayload emptyTagPayload =
                new TagNetworkSerialization.NetworkPayload(Map.of(EMPTY_ITEM_TAG_ID, ids()));
        var emptyTag = emptyTagPayload.resolve(BuiltInRegistries.ITEM);
        assertEquals(
                java.util.List.of(),
                emptyTag.tags().get(TagKey.create(Registries.ITEM, EMPTY_ITEM_TAG_ID)));
    }

    @Test
    void playbackSendsNativeTagPacketWithoutChangingRegistryOrMemberIds() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id134UpdateTagsPacketDefinition();
        var codec = packetCodec(protocol);
        ClientboundUpdateTagsPacket expected = new ClientboundUpdateTagsPacket(populatedRegistryTags());
        byte[] payload = packetBody(protocol, expected);

        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, rewriter());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundUpdateTagsPacket rewritten = assertInstanceOf(
                ClientboundUpdateTagsPacket.class, result.packet().orElseThrow());
        PacketCodec.EncodedPacket encoded = definition.encode(
                rewritten, descriptor(), codec);
        assertEquals(PacketPhase.PLAY, encoded.phase());
        assertEquals(PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
        assertEquals(PACKET_ID, encoded.packetId());

        ClientboundUpdateTagsPacket actual = assertInstanceOf(
                ClientboundUpdateTagsPacket.class,
                decodePacket(protocol, packetWire(encoded.payload())));
        assertPacketTagsEqual(expected, actual);
    }

    @Test
    void nativeCodecRejectsTruncatedBodiesAndLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, new ClientboundUpdateTagsPacket(populatedRegistryTags()));
        try {
            byte[] wire = bytes(encoded);
            for (int length = 0; length < wire.length; length++) {
                ByteBuf prefix = Unpooled.wrappedBuffer(Arrays.copyOf(wire, length));
                try {
                    assertThrows(RuntimeException.class, () -> decodePacket(protocol, prefix));
                } finally {
                    prefix.release();
                }
            }
        } finally {
            encoded.release();
        }

        ByteBuf withFollowingByte = encodePacket(
                protocol, new ClientboundUpdateTagsPacket(populatedRegistryTags()));
        try {
            withFollowingByte.writeByte(0x7A);
            ClientboundUpdateTagsPacket decoded = assertInstanceOf(
                    ClientboundUpdateTagsPacket.class,
                    decodePacket(protocol, withFollowingByte));
            assertFalse(decoded.getTags().isEmpty());
            assertEquals(0x7A, withFollowingByte.readUnsignedByte());
            assertFalse(withFollowingByte.isReadable());
        } finally {
            withFollowingByte.release();
        }
    }

    @Test
    void rejectsDescriptorMismatchBeforeNativeCodecUse() {
        var definition = new Id134UpdateTagsPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID + 1), codec, rewriter()));
        assertEquals(0, decodes.get());
    }

    private static Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload>
            populatedRegistryTags() {
        int stoneId = BuiltInRegistries.ITEM.getId(Items.STONE);
        int dirtId = BuiltInRegistries.ITEM.getId(Items.DIRT);

        Map<Identifier, IntList> itemTags = new HashMap<>();
        itemTags.put(ITEM_TAG_ID, ids(stoneId, dirtId, BuiltInRegistries.ITEM.size() + 100));

        Map<Identifier, IntList> biomeTags = new HashMap<>();
        biomeTags.put(Identifier.parse("replay_test:varint_boundaries"), ids(WIRE_ID_BOUNDARIES));

        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags =
                new HashMap<>();
        tags.put(Registries.ITEM, new TagNetworkSerialization.NetworkPayload(itemTags));
        tags.put(Registries.BIOME, new TagNetworkSerialization.NetworkPayload(biomeTags));
        tags.put(
                customRegistryKey(),
                new TagNetworkSerialization.NetworkPayload(Map.of(EMPTY_ITEM_TAG_ID, ids())));
        tags.put(Registries.TRIM_PATTERN, TagNetworkSerialization.NetworkPayload.EMPTY);
        return tags;
    }

    private static ResourceKey<? extends Registry<?>> customRegistryKey() {
        return ResourceKey.createRegistryKey(Identifier.parse("replay_test:custom_registry"));
    }

    private static IntList ids(int... values) {
        IntList result = new IntArrayList(values.length);
        for (int value : values) {
            result.add(value);
        }
        return result;
    }

    private static void assertPacketTagsEqual(
            ClientboundUpdateTagsPacket expected, ClientboundUpdateTagsPacket actual) {
        assertEquals(expected.getTags().keySet(), actual.getTags().keySet());
        for (var expectedEntry : expected.getTags().entrySet()) {
            var actualPayload = actual.getTags().get(expectedEntry.getKey());
            assertTrue(actualPayload != null);
            assertEquals(expectedEntry.getValue().isEmpty(), actualPayload.isEmpty());
            assertEquals(expectedEntry.getValue().size(), actualPayload.size());
            assertArrayEquals(
                    encodeNetworkPayload(expectedEntry.getValue()),
                    encodeNetworkPayload(actualPayload),
                    expectedEntry.getKey().toString());
        }
    }

    private static byte[] encodeNetworkPayload(TagNetworkSerialization.NetworkPayload payload) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            payload.write(new net.minecraft.network.FriendlyByteBuf(buffer));
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static ClientboundUpdateTagsPacket assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundUpdateTagsPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded.duplicate()));
            ClientboundUpdateTagsPacket decoded = assertInstanceOf(
                    ClientboundUpdateTagsPacket.class, decodePacket(protocol, encoded));
            assertFalse(encoded.isReadable());
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static byte[] packetBody(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf packetWire(byte[] payload) {
        ByteBuf wire = Unpooled.buffer();
        VarInt.write(wire, PACKET_ID);
        wire.writeBytes(payload);
        return wire;
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            return encoded;
        } catch (RuntimeException | Error failure) {
            encoded.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static PacketCodec<Object> packetCodec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ByteBuf wire = encodePacket(protocol, assertInstanceOf(Packet.class, packet));
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
                ByteBuf wire = packetWire(frame.payload());
                try {
                    return decodePacket(protocol, wire);
                } finally {
                    wire.release();
                }
            }
        };
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("descriptor mismatch must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("descriptor mismatch must not decode");
            }
        };
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(PACKET_ID);
    }

    private static PacketDescriptor descriptor(int packetId) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decodePacket(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
