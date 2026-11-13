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
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameRuleValuesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.gamerules.GameRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id039GameRuleValuesPacketDefinitionTest {

    private static final int PACKET_ID = 39;
    private static final String TYPE_NAME = "play.clientbound.minecraft:game_rule_values";
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
    void classifiesGameRuleValuesAsRecordedViewerLocalState() {
        var definition = new Id039GameRuleValuesPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x27, Id039GameRuleValuesPacketDefinition.PACKET_ID);
        assertEquals("minecraft:game_rule_values", Id039GameRuleValuesPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInWorldDomainCatalogAndPaperRegistry() {
        var domainMatches = WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id039GameRuleValuesPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id039GameRuleValuesPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
    }

    @Test
    void dropsEveryRuleMapShapeBeforeDecodingOrEncoding() {
        var definition = new Id039GameRuleValuesPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        Map<ResourceKey<GameRule<?>>, String> singleton = new LinkedHashMap<>();
        var builtInEntry = BuiltInRegistries.GAME_RULE.entrySet().iterator().next();
        singleton.put(builtInEntry.getKey(), builtInEntry.getValue().defaultValue().toString());
        Map<ResourceKey<GameRule<?>>, String> withCustomRule = allBuiltInRules();
        withCustomRule.put(customRuleKey(), "custom-value");

        for (Map<ResourceKey<GameRule<?>>, String> values : List
                .<Map<ResourceKey<GameRule<?>>, String>>of(
                        Map.of(), singleton, allBuiltInRules(), withCustomRule)) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(packetBody(new ClientboundGameRuleValuesPacket(values))),
                    descriptor(),
                    codec,
                    rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        for (byte[] malformed : new byte[][] {
            new byte[0],
            new byte[] {(byte) 0x80}, // incomplete map-size VarInt
            new byte[] {1}, // one entry with no key or value
            new byte[] {1, 0} // truncated resource identifier
        }) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(malformed), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void validatesDescriptorAndArgumentBoundariesBeforeApplyingLocalPolicy() {
        var definition = new Id039GameRuleValuesPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), codec, null));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(PACKET_ID + 1, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec,
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(PACKET_ID, false, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec,
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE),
                codec,
                rewriter()));
    }

    @Test
    void nativeProtocolRoundTripsEmptyBuiltInAndCustomRuleMaps() {
        ProtocolInfo<?> protocol = protocol();
        Map<ResourceKey<GameRule<?>>, String> singleton = new LinkedHashMap<>();
        var builtInEntry = BuiltInRegistries.GAME_RULE.entrySet().iterator().next();
        singleton.put(builtInEntry.getKey(), builtInEntry.getValue().defaultValue().toString());
        Map<ResourceKey<GameRule<?>>, String> allRules = allBuiltInRules();
        Map<ResourceKey<GameRule<?>>, String> customRule = new LinkedHashMap<>(allRules);
        customRule.put(customRuleKey(), "serialized custom rule value");

        assertRoundTrip(protocol, Map.of());
        assertRoundTrip(protocol, singleton);
        assertRoundTrip(protocol, allRules);
        assertRoundTrip(protocol, customRule);
    }

    @Test
    void nativeProtocolRejectsTruncatedGameRuleMaps() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeMap = packetBody(new ClientboundGameRuleValuesPacket(allBuiltInRules()));

        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {(byte) 0x80});
        assertMalformed(protocol, new byte[] {1});
        assertMalformed(protocol, new byte[] {1, 0});
        assertMalformed(protocol, java.util.Arrays.copyOf(completeMap, completeMap.length - 1));
    }

    private static Map<ResourceKey<GameRule<?>>, String> allBuiltInRules() {
        Map<ResourceKey<GameRule<?>>, String> values = new LinkedHashMap<>();
        BuiltInRegistries.GAME_RULE.entrySet().forEach(entry ->
                values.put(entry.getKey(), entry.getValue().defaultValue().toString()));
        assertFalse(values.isEmpty(), "the bootstrapped built-in game-rule registry must not be empty");
        return values;
    }

    private static ResourceKey<GameRule<?>> customRuleKey() {
        return ResourceKey.create(
                Registries.GAME_RULE,
                Identifier.fromNamespaceAndPath("replay_test", "custom_rule"));
    }

    private static void assertRoundTrip(
            ProtocolInfo<?> protocol,
            Map<ResourceKey<GameRule<?>>, String> expectedValues) {
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundGameRuleValuesPacket(expectedValues));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));

            ClientboundGameRuleValuesPacket decoded = assertInstanceOf(
                    ClientboundGameRuleValuesPacket.class, decode(protocol, encoded));
            assertEquals(expectedValues, decoded.values());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
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

    private static byte[] packetBody(Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol(), packet);
        try {
            assertEquals(PACKET_ID, readVarInt(encoded));
            byte[] body = new byte[encoded.readableBytes()];
            encoded.readBytes(body);
            return body;
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
        return descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            int packetId,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                false,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local game_rule_values must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local game_rule_values must not decode");
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
