package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id116SoundEntityPacketDefinitionTest {

    private static final int PACKET_ID = 116;
    private static final String TYPE_NAME = "play.clientbound.minecraft:sound_entity";
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
    void classifiesSoundEntityAsEphemeralIdentityBearingEffect() {
        var definition = new Id116SoundEntityPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x74, Id116SoundEntityPacketDefinition.PACKET_ID);
        assertEquals("minecraft:sound_entity", Id116SoundEntityPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
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
        assertInstanceOf(Id116SoundEntityPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id116SoundEntityPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void rewritesEntityIdAndPreservesRegisteredAndInlineSoundEventForms() {
        var definition = new Id116SoundEntityPacketDefinition();
        var context = rewriter();
        List<Holder<SoundEvent>> sounds = soundForms();
        SoundSource[] categories = SoundSource.values();
        ProtocolInfo<?> protocol = protocol();
        PacketCodec<Object> codec = nativePacketCodec(protocol);

        for (int index = 0; index < categories.length; index++) {
            Holder<SoundEvent> sound = sounds.get(index % sounds.size());
            SoundSource source = categories[index];
            int recordedEntityId = index == 0 ? -1 : index * 37;
            float volume = index % 3 == 0 ? 0.0F : 1.25F + index;
            float pitch = index % 2 == 0 ? 0.5F : 2.5F + index;
            long seed = index == 0 ? Long.MIN_VALUE : Long.MAX_VALUE - index;
            int expectedEntityId = context.mapEntityId(recordedEntityId);

            PacketRewriteResult result = definition.handlePlayback(
                    frame(sound, source, recordedEntityId, volume, pitch, seed),
                    descriptor(),
                    codec,
                    context);

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            ClientboundSoundEntityPacket rewritten = assertInstanceOf(
                    ClientboundSoundEntityPacket.class, result.packet().orElseThrow());
            assertEquals(expectedEntityId, rewritten.getId());
            assertNotEquals(recordedEntityId, rewritten.getId());
            assertEquals(source, rewritten.getSource());
            assertEquals(sound.value(), rewritten.getSound().value());
            assertEquals(Holder.Kind.DIRECT, rewritten.getSound().kind());
            assertFloatBitsEqual(volume, rewritten.getVolume());
            assertFloatBitsEqual(pitch, rewritten.getPitch());
            assertEquals(seed, rewritten.getSeed());

            PacketCodec.EncodedPacket output = definition.encode(
                    rewritten, descriptor(), codec);
            assertEquals(PacketPhase.PLAY, output.phase());
            assertEquals(PacketDescriptor.Direction.CLIENTBOUND, output.direction());
            assertEquals(PACKET_ID, output.packetId());
            assertOutputBody(output.payload(), sound.value(), source,
                    expectedEntityId, volume, pitch, seed);
        }
    }

    @Test
    void preservesFiniteFloatValuesOutsideVanillaPresentationRanges() {
        var definition = new Id116SoundEntityPacketDefinition();
        ProtocolInfo<?> protocol = protocol();
        PacketCodec<Object> codec = nativePacketCodec(protocol);
        var context = rewriter();
        Holder<SoundEvent> sound = Holder.direct(SoundEvent.createFixedRangeEvent(
                Identifier.parse("replay:test_fixed_range"), 23.5F));

        assertRewritten(definition, codec, context, sound, SoundSource.PLAYERS,
                901, -3.75F, 7.5F, Long.MIN_VALUE);
    }

    @Test
    void preservesVolumeAndPitchFloatBitPatternsAndSeedBoundaries() {
        var definition = new Id116SoundEntityPacketDefinition();
        ProtocolInfo<?> protocol = protocol();
        PacketCodec<Object> codec = nativePacketCodec(protocol);
        Holder<SoundEvent> sound = SoundEvents.GENERIC_EXPLODE;

        int[][] floatBits = {
            {0x00000000, 0x3F000000},
            {0x80000000, 0x40000000},
            {0x00000001, 0x7F7FFFFF},
            {0xFF7FFFFF, 0x00800000},
            {0x3EAAAAAB, 0xBF000000}
        };
        long[] seeds = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE};

        for (int index = 0; index < floatBits.length; index++) {
            float volume = Float.intBitsToFloat(floatBits[index][0]);
            float pitch = Float.intBitsToFloat(floatBits[index][1]);
            var context = rewriter();
            int recordedEntityId = Integer.MIN_VALUE + index;
            int mappedEntityId = context.mapEntityId(recordedEntityId);
            PacketRewriteResult result = definition.handlePlayback(
                    frame(sound, SoundSource.AMBIENT, recordedEntityId,
                            volume, pitch, seeds[index]),
                    descriptor(), codec, context);
            ClientboundSoundEntityPacket rewritten = assertInstanceOf(
                    ClientboundSoundEntityPacket.class, result.packet().orElseThrow());

            assertEquals(mappedEntityId, rewritten.getId());
            assertFloatBitsEqual(volume, rewritten.getVolume());
            assertFloatBitsEqual(pitch, rewritten.getPitch());
            assertEquals(seeds[index], rewritten.getSeed());
        }
    }

    @Test
    void rejectsNonFiniteSoundValuesBeforeRebuilding() {
        var definition = new Id116SoundEntityPacketDefinition();
        ProtocolInfo<?> protocol = protocol();
        PacketCodec<Object> codec = nativePacketCodec(protocol);
        List<RawPacketFrame> invalidFrames = List.of(
                frame(SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 42,
                        Float.NaN, 1.0F, 1L),
                frame(SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 42,
                        1.0F, Float.POSITIVE_INFINITY, 1L),
                frame(Holder.direct(SoundEvent.createFixedRangeEvent(
                                Identifier.parse("replay:invalid_range"), Float.NEGATIVE_INFINITY)),
                        SoundSource.HOSTILE, 42, 1.0F, 1.0F, 1L));

        for (RawPacketFrame frame : invalidFrames) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame, descriptor(), codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolUsesTheDeclaredFieldOrderAndConsumesOneSoundPacket() {
        ProtocolInfo<?> protocol = protocol();
        Holder<SoundEvent> sound = Holder.direct(SoundEvent.createFixedRangeEvent(
                Identifier.parse("replay:wire_order"), 18.25F));
        RawPacketFrame frame = frame(
                sound, SoundSource.VOICE, 0x1234, 0.75F, 1.5F, 0x1020304050607080L);
        PacketCodec<Object> codec = nativePacketCodec(protocol);
        ClientboundSoundEntityPacket original = assertInstanceOf(
                ClientboundSoundEntityPacket.class, codec.decode(frame));
        var definition = new Id116SoundEntityPacketDefinition();
        var context = rewriter();
        PacketRewriteResult rewrite = definition.rewrite(original, descriptor(), context);
        ClientboundSoundEntityPacket rewritten = assertInstanceOf(
                ClientboundSoundEntityPacket.class, rewrite.packet().orElseThrow());
        PacketCodec.EncodedPacket encoded = definition.encode(rewritten, descriptor(), codec);

        assertOutputBody(
                encoded.payload(), sound.value(), SoundSource.VOICE,
                context.mapEntityId(0x1234), 0.75F, 1.5F, 0x1020304050607080L);

        ByteBuf fullWire = Unpooled.buffer();
        try {
            VarInt.write(fullWire, PACKET_ID);
            fullWire.writeBytes(encoded.payload());
            fullWire.writeByte(0x7A);
            ClientboundSoundEntityPacket decoded = assertInstanceOf(
                    ClientboundSoundEntityPacket.class, decode(protocol, fullWire));
            assertEquals(context.mapEntityId(0x1234), decoded.getId());
            assertEquals(0x7A, fullWire.readUnsignedByte());
            assertFalse(fullWire.isReadable());
        } finally {
            fullWire.release();
        }
    }

    @Test
    void nativeProtocolRejectsTruncatedSoundEventAndPacketFields() {
        ProtocolInfo<?> protocol = protocol();
        RawPacketFrame valid = frame(
                Holder.direct(SoundEvent.createFixedRangeEvent(
                        Identifier.parse("replay:truncation"), 12.0F)),
                SoundSource.BLOCKS, 77, 0.5F, 1.25F, 17L);
        PacketCodec<Object> codec = nativePacketCodec(protocol);
        byte[] body = valid.payload();

        for (int length = 0; length < body.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(body, length);
            assertThrows(RuntimeException.class,
                    () -> codec.decode(frame(truncated)));
        }
    }

    @Test
    void nativeProtocolRejectsUnknownSoundRegistryIdsAndCategories() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, packetBodyBytes(buffer -> {
            VarInt.write(buffer, Integer.MAX_VALUE);
        }));

        assertMalformed(protocol, packetBodyBytes(buffer -> {
            SoundEvent.STREAM_CODEC.encode(
                    new RegistryFriendlyByteBuf(buffer, registryAccess()),
                    Holder.direct(SoundEvent.createVariableRangeEvent(
                            Identifier.parse("replay:invalid_category"))));
            VarInt.write(buffer, Integer.MAX_VALUE);
        }));
    }

    @Test
    void rejectsDescriptorMismatchesBeforeRewriting() {
        var definition = new Id116SoundEntityPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        RawPacketFrame frame = frame(SoundEvents.GENERIC_EXPLODE,
                SoundSource.HOSTILE, 10, 1.0F, 1.0F, 0L);
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame, mismatch, codec, rewriter()));
        }
    }

    private static List<Holder<SoundEvent>> soundForms() {
        return List.of(
                SoundEvents.GENERIC_EXPLODE,
                Holder.direct(SoundEvent.createVariableRangeEvent(
                        Identifier.parse("replay:variable_sound"))),
                Holder.direct(SoundEvent.createFixedRangeEvent(
                        Identifier.parse("replay:fixed_sound"), 23.5F)));
    }

    private static void assertRewritten(
            Id116SoundEntityPacketDefinition definition,
            PacketCodec<Object> codec,
            Paper26_2ReplayPacketRewriter context,
            Holder<SoundEvent> sound,
            SoundSource source,
            int recordedEntityId,
            float volume,
            float pitch,
            long seed) {
        int expectedEntityId = context.mapEntityId(recordedEntityId);
        PacketRewriteResult result = definition.handlePlayback(
                frame(sound, source, recordedEntityId, volume, pitch, seed),
                descriptor(), codec, context);
        ClientboundSoundEntityPacket rewritten = assertInstanceOf(
                ClientboundSoundEntityPacket.class, result.packet().orElseThrow());

        assertEquals(expectedEntityId, rewritten.getId());
        assertEquals(source, rewritten.getSource());
        assertEquals(sound.value(), rewritten.getSound().value());
        assertFloatBitsEqual(volume, rewritten.getVolume());
        assertFloatBitsEqual(pitch, rewritten.getPitch());
        assertEquals(seed, rewritten.getSeed());
    }

    private static RawPacketFrame frame(
            Holder<SoundEvent> sound,
            SoundSource source,
            int entityId,
            float volume,
            float pitch,
            long seed) {
        ByteBuf encoded = packetBody(buffer -> {
            RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(buffer, registryAccess());
            SoundEvent.STREAM_CODEC.encode(output, sound);
            output.writeEnum(source);
            output.writeVarInt(entityId);
            output.writeFloat(volume);
            output.writeFloat(pitch);
            output.writeLong(seed);
        });
        try {
            return frame(bytes(encoded));
        } finally {
            encoded.release();
        }
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static ByteBuf packetBody(java.util.function.Consumer<ByteBuf> writer) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            VarInt.write(buffer, PACKET_ID);
            writer.accept(buffer);
            VarInt.read(buffer);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.release();
            throw failure;
        }
    }

    private static byte[] packetBodyBytes(java.util.function.Consumer<ByteBuf> writer) {
        ByteBuf buffer = packetBody(writer);
        try {
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void assertOutputBody(
            byte[] payload,
            SoundEvent expectedSound,
            SoundSource expectedSource,
            int expectedEntityId,
            float expectedVolume,
            float expectedPitch,
            long expectedSeed) {
        ByteBuf source = Unpooled.wrappedBuffer(payload);
        try {
            RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(source, registryAccess());
            Holder<SoundEvent> sound = SoundEvent.STREAM_CODEC.decode(wire);
            assertEquals(Holder.Kind.DIRECT, sound.kind());
            assertEquals(expectedSound, sound.value());
            assertEquals(expectedSource, wire.readEnum(SoundSource.class));
            assertEquals(expectedEntityId, wire.readVarInt());
            assertFloatBitsEqual(expectedVolume, wire.readFloat());
            assertFloatBitsEqual(expectedPitch, wire.readFloat());
            assertEquals(expectedSeed, wire.readLong());
            assertFalse(wire.isReadable());
        } finally {
            source.release();
        }
    }

    private static PacketCodec<Object> nativePacketCodec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ByteBuf encoded = Unpooled.buffer();
                try {
                    Id116SoundEntityPacketDefinitionTest.encode(
                            protocol, encoded, assertInstanceOf(Packet.class, packet));
                    int packetId = VarInt.read(encoded);
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            packetId,
                            bytes(encoded));
                } finally {
                    encoded.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                ByteBuf encoded = Unpooled.buffer();
                try {
                    VarInt.write(encoded, frame.packetId());
                    encoded.writeBytes(frame.payload());
                    return Id116SoundEntityPacketDefinitionTest.decode(protocol, encoded);
                } finally {
                    encoded.release();
                }
            }
        };
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
        return descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor descriptor(
            String typeName, int packetId, PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/" + typeName,
                scope);
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
            input.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void assertFloatBitsEqual(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
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
