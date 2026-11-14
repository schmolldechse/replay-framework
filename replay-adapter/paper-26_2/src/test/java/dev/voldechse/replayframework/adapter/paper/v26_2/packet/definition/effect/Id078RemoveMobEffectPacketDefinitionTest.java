package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.effect.MobEffect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id078RemoveMobEffectPacketDefinitionTest {

    private static final int PACKET_ID = 78;
    private static final String TYPE_NAME = "play.clientbound.minecraft:remove_mob_effect";
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
    void classifiesEffectRemovalAsCheckpointRelevantIdentityBearingSceneState() {
        var definition = new Id078RemoveMobEffectPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x4E, Id078RemoveMobEffectPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:remove_mob_effect",
                Id078RemoveMobEffectPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInEffectDomainCatalogAndPaperRegistry() {
        var domainMatches = EffectPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id078RemoveMobEffectPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id078RemoveMobEffectPacketDefinition.class, catalogMatches.getFirst());

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
    void rewritesOnlyTheEntityIdentityAndRetainsEveryRegisteredEffectHolder() {
        var definition = new Id078RemoveMobEffectPacketDefinition();
        var context = rewriter();
        int[] entityIds = {
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_384, Integer.MAX_VALUE
        };
        List<Holder<MobEffect>> effects = effectHolders();
        assertFalse(effects.isEmpty());

        for (Holder<MobEffect> effect : effects) {
            for (int entityId : entityIds) {
                var original = packet(entityId, effect);
                var read = definition.read(original);
                assertEquals(entityId, read.entityId());
                assertSame(effect, read.effect());

                int replayEntityId = context.mapEntityId(entityId);
                var rewritten = definition.rewrite(read, context).value().orElseThrow();
                assertEquals(replayEntityId, rewritten.entityId());
                assertNotEquals(entityId, rewritten.entityId());
                assertSame(effect, rewritten.effect());

                ClientboundRemoveMobEffectPacket rebuilt = definition.rebuild(rewritten);
                assertEquals(replayEntityId, rebuilt.entityId());
                assertSame(effect, rebuilt.effect());
            }
        }
    }

    @Test
    void requiresAnEffectHolder() {
        assertThrows(NullPointerException.class,
                () -> new Id078RemoveMobEffectPacketDefinition.Value(1, null));
    }

    @Test
    void nativeProtocolUsesIdSeventyEightAndRegistryEncodesEveryEffect() {
        ProtocolInfo<?> protocol = protocol();
        int[] entityIds = {
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_384, Integer.MAX_VALUE
        };
        List<Holder<MobEffect>> effects = effectHolders();

        for (Holder<MobEffect> effect : effects) {
            int effectId = BuiltInRegistries.MOB_EFFECT.getId(effect.value());
            for (int entityId : entityIds) {
                ClientboundRemoveMobEffectPacket expected = packet(entityId, effect);
                ByteBuf encoded = encodePacket(protocol, expected);
                try {
                    byte[] expectedWire = bytes(encoded);
                    ByteBuf wire = encoded.duplicate();
                    assertEquals(PACKET_ID, readVarInt(wire));
                    assertEquals(entityId, readVarInt(wire));
                    assertEquals(effectId, readVarInt(wire));
                    assertFalse(wire.isReadable());

                    ClientboundRemoveMobEffectPacket decoded = assertInstanceOf(
                            ClientboundRemoveMobEffectPacket.class, decode(protocol, encoded));
                    assertEquals(entityId, decoded.entityId());
                    assertSame(effect, decoded.effect());
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
    }

    @Test
    void nativeProtocolRejectsUnknownEffectIdsAndEveryTruncatedBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);
        assertMalformed(protocol, new byte[] {1}); // entity ID only
        assertMalformed(protocol, new byte[] {1, (byte) 0x80}); // incomplete effect ID

        ByteBuf complete = encodePacket(protocol, packet(128, effectHolders().getFirst()));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] body = new byte[wire.readableBytes()];
            wire.readBytes(body);
            for (int length = 0; length < body.length; length++) {
                assertMalformed(protocol, Arrays.copyOf(body, length));
            }
        } finally {
            complete.release();
        }

        ByteBuf unknownEffect = Unpooled.buffer();
        try {
            writeVarInt(unknownEffect, PACKET_ID);
            writeVarInt(unknownEffect, 1);
            writeVarInt(unknownEffect, BuiltInRegistries.MOB_EFFECT.size());
            assertThrows(RuntimeException.class, () -> decode(protocol, unknownEffect));
        } finally {
            unknownEffect.release();
        }
    }

    @Test
    void playbackRewritesAndEncodesTheEffectRemoval() {
        var definition = new Id078RemoveMobEffectPacketDefinition();
        var context = rewriter();
        Holder<MobEffect> effect = effectHolders().getFirst();
        ClientboundRemoveMobEffectPacket original = packet(401, effect);
        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundRemoveMobEffectPacket rewritten = assertInstanceOf(
                ClientboundRemoveMobEffectPacket.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(401), rewritten.entityId());
        assertSame(effect, rewritten.effect());
        PacketCodec.EncodedPacket encoded = definition.encode(
                rewritten, descriptor(), codec(rewritten));
        assertEquals(PacketPhase.PLAY, encoded.phase());
        assertEquals(PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
        assertEquals(PACKET_ID, encoded.packetId());
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncoding() {
        var definition = new Id078RemoveMobEffectPacketDefinition();
        ClientboundRemoveMobEffectPacket original = packet(402, effectHolders().getFirst());

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

    private static List<Holder<MobEffect>> effectHolders() {
        return BuiltInRegistries.MOB_EFFECT.stream()
                .map(BuiltInRegistries.MOB_EFFECT::wrapAsHolder)
                .toList();
    }

    private static ClientboundRemoveMobEffectPacket packet(
            int entityId, Holder<MobEffect> effect) {
        return new ClientboundRemoveMobEffectPacket(entityId, effect);
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

    private static PacketCodec<Object> codec(ClientboundRemoveMobEffectPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundRemoveMobEffectPacket.class, packet);
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

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf input = Unpooled.buffer();
        try {
            writeVarInt(input, PACKET_ID);
            input.writeBytes(body);
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
