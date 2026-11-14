package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

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
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.effect.MobEffect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id132UpdateMobEffectPacketDefinitionTest {

    private static final int PACKET_ID = 132;
    private static final String TYPE_NAME = "play.clientbound.minecraft:update_mob_effect";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000132");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000133");
    private static final int[] ENTITY_ID_BOUNDARIES = {
        Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_384, Integer.MAX_VALUE
    };
    private static final int[] VAR_INT_BOUNDARIES = {
        Integer.MIN_VALUE, -1, 0, 1, 127, 128, 255, 256, 16_384, Integer.MAX_VALUE
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersMobEffectUpdatesAsCheckpointRelevantEntityState() {
        var definition = new Id132UpdateMobEffectPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x84, Id132UpdateMobEffectPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:update_mob_effect",
                Id132UpdateMobEffectPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.RUNNING_EFFECT,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EffectPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id132UpdateMobEffectPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id132UpdateMobEffectPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeCodecUsesPacketIdRegistryEffectAndAllIntegerFields() {
        ProtocolInfo<?> protocol = protocol();
        List<Holder<MobEffect>> effects = effectHolders();
        assertFalse(effects.isEmpty());

        for (int effectIndex = 0; effectIndex < effects.size(); effectIndex++) {
            Holder<MobEffect> effect = effects.get(effectIndex);
            int entityId = ENTITY_ID_BOUNDARIES[effectIndex % ENTITY_ID_BOUNDARIES.length];
            int amplifier = VAR_INT_BOUNDARIES[effectIndex % VAR_INT_BOUNDARIES.length];
            int duration = VAR_INT_BOUNDARIES[
                    (effectIndex * 3 + 1) % VAR_INT_BOUNDARIES.length];
            int flags = (effectIndex * 37) & 0xFF;
            byte[] body = body(entityId, effect, amplifier, duration, flags);
            ByteBuf wire = packetWire(body);
            try {
                RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(
                        wire.duplicate(), registryAccess());
                assertEquals(PACKET_ID, fields.readVarInt());
                assertEquals(entityId, fields.readVarInt());
                assertEquals(effect, MobEffect.STREAM_CODEC.decode(fields));
                assertEquals(amplifier, fields.readVarInt());
                assertEquals(duration, fields.readVarInt());
                assertEquals(flags, fields.readUnsignedByte());
                assertFalse(fields.isReadable());

                ClientboundUpdateMobEffectPacket decoded = assertInstanceOf(
                        ClientboundUpdateMobEffectPacket.class, decode(protocol, wire));
                assertPacketFields(decoded, entityId, effect, amplifier, duration, flags);
                assertFalse(wire.isReadable());

                ByteBuf reencoded = encodePacket(protocol, decoded);
                try {
                    assertArrayEquals(packetWireBytes(body), bytes(reencoded));
                } finally {
                    reencoded.release();
                }
            } finally {
                wire.release();
            }
        }
    }

    @Test
    void nativeCodecRetainsEveryFlagsByteIncludingReservedBits() {
        ProtocolInfo<?> protocol = protocol();
        Holder<MobEffect> effect = effectHolders().getFirst();

        for (int flags = 0; flags <= 0xFF; flags++) {
            int entityId = flags - 128;
            int amplifier = flags - 128;
            int duration = 128 - flags;
            byte[] body = body(entityId, effect, amplifier, duration, flags);
            ClientboundUpdateMobEffectPacket decoded = assertInstanceOf(
                    ClientboundUpdateMobEffectPacket.class,
                    decodePayload(protocol, body));

            assertPacketFields(decoded, entityId, effect, amplifier, duration, flags);
            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(packetWireBytes(body), bytes(reencoded));
            } finally {
                reencoded.release();
            }
        }
    }

    @Test
    void playbackRewritesEntityIdentityAndPreservesEveryFlagsByte() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id132UpdateMobEffectPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = codec(protocol);
        Holder<MobEffect> effect = effectHolders().getFirst();

        for (int flags = 0; flags <= 0xFF; flags++) {
            int recordedEntityId = flags + 1_000;
            int amplifier = 200 - flags;
            int duration = flags - 100;
            byte[] recordedPayload = body(recordedEntityId, effect, amplifier, duration, flags);
            PacketRewriteResult result = definition.handlePlayback(
                    frame(recordedPayload), descriptor(), codec, context);

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            PacketCodec.EncodedPacket encoded = definition.encode(
                    result.packet().orElseThrow(), descriptor(), codec);
            assertEquals(PacketPhase.PLAY, encoded.phase());
            assertEquals(PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
            assertEquals(PACKET_ID, encoded.packetId());

            ClientboundUpdateMobEffectPacket decoded = assertInstanceOf(
                    ClientboundUpdateMobEffectPacket.class,
                    decodePayload(protocol, encoded.payload()));
            int playbackEntityId = context.mapEntityId(recordedEntityId);
            assertNotEquals(recordedEntityId, playbackEntityId);
            assertPacketFields(decoded, playbackEntityId, effect, amplifier, duration, flags);
            assertEquals(flags, Byte.toUnsignedInt(encoded.payload()[encoded.payload().length - 1]));
        }
    }

    @Test
    void playbackPreservesAmplifierDurationAndEntityVarIntBoundaries() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id132UpdateMobEffectPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = codec(protocol);
        Holder<MobEffect> effect = effectHolders().getFirst();

        for (int entityId : ENTITY_ID_BOUNDARIES) {
            for (int amplifier : VAR_INT_BOUNDARIES) {
                for (int duration : VAR_INT_BOUNDARIES) {
                    int flags = 0xA5;
                    PacketRewriteResult result = definition.handlePlayback(
                            frame(body(entityId, effect, amplifier, duration, flags)),
                            descriptor(),
                            codec,
                            context);
                    PacketCodec.EncodedPacket encoded = definition.encode(
                            result.packet().orElseThrow(), descriptor(), codec);
                    ClientboundUpdateMobEffectPacket decoded = assertInstanceOf(
                            ClientboundUpdateMobEffectPacket.class,
                            decodePayload(protocol, encoded.payload()));

                    assertPacketFields(
                            decoded,
                            context.mapEntityId(entityId),
                            effect,
                            amplifier,
                            duration,
                            flags);
                }
            }
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedPayloadPrefixAndUnknownEffectIds() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(23, effectHolders().getFirst(), 4, -1, 0x0F);

        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, Arrays.copyOf(completeBody, length));
        }

        ByteBuf unknownEffect = Unpooled.buffer();
        try {
            VarInt.write(unknownEffect, PACKET_ID);
            VarInt.write(unknownEffect, 1);
            VarInt.write(unknownEffect, BuiltInRegistries.MOB_EFFECT.size());
            VarInt.write(unknownEffect, 0);
            VarInt.write(unknownEffect, 20);
            unknownEffect.writeByte(0);
            assertThrows(RuntimeException.class, () -> decode(protocol, unknownEffect));
        } finally {
            unknownEffect.release();
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        int entityId = 24;
        Holder<MobEffect> effect = effectHolders().getFirst();
        ByteBuf wire = packetWire(body(entityId, effect, 2, 80, 0x0F));
        try {
            wire.writeByte(0x7A);
            ClientboundUpdateMobEffectPacket decoded = assertInstanceOf(
                    ClientboundUpdateMobEffectPacket.class, decode(protocol, wire));

            assertPacketFields(decoded, entityId, effect, 2, 80, 0x0F);
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void validatesDescriptorsBeforeDecodingOrEncoding() {
        var definition = new Id132UpdateMobEffectPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);
        byte[] body = body(2, effectHolders().getFirst(), 1, 20, 0);

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(body), descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(body), descriptor(TYPE_NAME.replace("update_mob_effect", "other"), PACKET_ID, true),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(body), descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));

        ProtocolInfo<?> protocol = protocol();
        ClientboundUpdateMobEffectPacket nativePacket = assertInstanceOf(
                ClientboundUpdateMobEffectPacket.class,
                decodePayload(protocol, body));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                nativePacket, descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
        assertEquals(0, decodes.get());
    }

    private static List<Holder<MobEffect>> effectHolders() {
        return BuiltInRegistries.MOB_EFFECT.stream()
                .map(BuiltInRegistries.MOB_EFFECT::wrapAsHolder)
                .toList();
    }

    private static void assertPacketFields(
            ClientboundUpdateMobEffectPacket packet,
            int entityId,
            Holder<MobEffect> effect,
            int amplifier,
            int duration,
            int flags) {
        assertEquals(entityId, packet.getEntityId());
        assertEquals(effect, packet.getEffect());
        assertEquals(amplifier, packet.getEffectAmplifier());
        assertEquals(duration, packet.getEffectDurationTicks());
        assertEquals((flags & 0x01) != 0, packet.isEffectAmbient());
        assertEquals((flags & 0x02) != 0, packet.isEffectVisible());
        assertEquals((flags & 0x04) != 0, packet.effectShowsIcon());
        assertEquals((flags & 0x08) != 0, packet.shouldBlend());
    }

    private static byte[] body(
            int entityId,
            Holder<MobEffect> effect,
            int amplifier,
            int duration,
            int flags) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(buffer, registryAccess());
            output.writeVarInt(entityId);
            MobEffect.STREAM_CODEC.encode(output, effect);
            output.writeVarInt(amplifier);
            output.writeVarInt(duration);
            output.writeByte(flags);
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] packetWireBytes(byte[] body) {
        ByteBuf wire = packetWire(body);
        try {
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static ByteBuf packetWire(byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        VarInt.write(wire, PACKET_ID);
        wire.writeBytes(body);
        return wire;
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

    private static Object decodePayload(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = packetWire(body);
        try {
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
        ByteBuf wire = packetWire(body);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess()));
    }

    private static RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, true);
    }

    private static PacketDescriptor descriptor(String typeName, int packetId, boolean replayable) {
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
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundUpdateMobEffectPacket nativePacket = assertInstanceOf(
                        ClientboundUpdateMobEffectPacket.class, packet);
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

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
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
