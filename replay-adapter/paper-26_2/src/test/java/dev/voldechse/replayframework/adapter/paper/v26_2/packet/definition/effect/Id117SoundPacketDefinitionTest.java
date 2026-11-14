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
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id117SoundPacketDefinitionTest {

    private static final int PACKET_ID = 117;
    private static final String TYPE_NAME = "play.clientbound.minecraft:sound";
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
    void classifiesSoundAsEphemeralSafeScenePresentation() {
        var definition = new Id117SoundPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x75, Id117SoundPacketDefinition.PACKET_ID);
        assertEquals("minecraft:sound", Id117SoundPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, definition.classification().playbackScope());
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
        assertInstanceOf(Id117SoundPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id117SoundPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void preservesBothSoundHolderFormsEveryCategoryAndCompleteWirePayload() {
        var definition = new Id117SoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        List<Holder<SoundEvent>> sounds = soundForms();
        SoundSource[] categories = SoundSource.values();

        for (int index = 0; index < categories.length; index++) {
            Holder<SoundEvent> sound = sounds.get(index % sounds.size());
            int x = index % 2 == 0 ? Integer.MIN_VALUE + index : Integer.MAX_VALUE - index;
            int y = -100_000_001 + index;
            int z = 16_777_217 + index;
            float volume = index % 3 == 0 ? -0.0F : 1.25F + index;
            float pitch = index % 2 == 0 ? 0.5F : 2.5F + index;
            long seed = index == 0 ? Long.MIN_VALUE : Long.MAX_VALUE - index;

            PacketRewriteResult result = definition.handlePlayback(
                    frame(sound, categories[index], x, y, z, volume, pitch, seed),
                    descriptor(),
                    codec,
                    rewriter());

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            ClientboundSoundPacket rewritten = assertInstanceOf(
                    ClientboundSoundPacket.class, result.packet().orElseThrow());
            assertEquals(sound.value(), rewritten.getSound().value());
            assertEquals(Holder.Kind.DIRECT, rewritten.getSound().kind());
            assertEquals(categories[index], rewritten.getSource());
            assertFloatBitsEqual(volume, rewritten.getVolume());
            assertFloatBitsEqual(pitch, rewritten.getPitch());
            assertEquals(seed, rewritten.getSeed());

            PacketCodec.EncodedPacket output = definition.encode(rewritten, descriptor(), codec);
            assertEquals(PacketPhase.PLAY, output.phase());
            assertEquals(PacketDescriptor.Direction.CLIENTBOUND, output.direction());
            assertEquals(PACKET_ID, output.packetId());
            assertOutputBody(output.payload(), sound.value(), categories[index],
                    x, y, z, volume, pitch, seed);
        }
    }

    @Test
    void preservesEverySignedCoordinateIncludingValuesThatNativeFloatGettersLose() {
        var definition = new Id117SoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        int[] coordinates = {
            Integer.MIN_VALUE,
            Integer.MIN_VALUE + 1,
            -240_000_001,
            -16_777_217,
            -1,
            0,
            1,
            16_777_217,
            240_000_001,
            Integer.MAX_VALUE - 1,
            Integer.MAX_VALUE
        };

        for (int raw : coordinates) {
            RawPacketFrame frame = frame(
                    SoundEvents.GENERIC_EXPLODE,
                    SoundSource.PLAYERS,
                    raw,
                    raw,
                    raw,
                    1.0F,
                    1.0F,
                    0L);
            ClientboundSoundPacket nativePacket = assertInstanceOf(
                    ClientboundSoundPacket.class, codec.decode(frame));
            if (raw == 16_777_217) {
                assertNotEquals(raw, (int) (nativePacket.getX() * 8.0D));
            }

            ClientboundSoundPacket rebuilt = assertInstanceOf(
                    ClientboundSoundPacket.class,
                    definition.handlePlayback(frame, descriptor(), codec, rewriter())
                            .packet().orElseThrow());
            PacketCodec.EncodedPacket output = definition.encode(rebuilt, descriptor(), codec);
            assertOutputBody(output.payload(), SoundEvents.GENERIC_EXPLODE.value(),
                    SoundSource.PLAYERS, raw, raw, raw, 1.0F, 1.0F, 0L);
        }
    }

    @Test
    void preservesFiniteFloatBitPatternsRangesAndSeedBoundaries() {
        var definition = new Id117SoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        Holder<SoundEvent> sound = Holder.direct(SoundEvent.createFixedRangeEvent(
                Identifier.parse("replay:test_fixed_range"), 23.5F));
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
            long seed = seeds[index];
            ClientboundSoundPacket rebuilt = assertInstanceOf(
                    ClientboundSoundPacket.class,
                    definition.handlePlayback(
                                    frame(sound, SoundSource.VOICE, index, -index, index * 31,
                                            volume, pitch, seed),
                                    descriptor(), codec, rewriter())
                            .packet().orElseThrow());
            assertEquals(sound.value(), rebuilt.getSound().value());
            assertEquals(Holder.Kind.DIRECT, rebuilt.getSound().kind());
            assertFloatBitsEqual(volume, rebuilt.getVolume());
            assertFloatBitsEqual(pitch, rebuilt.getPitch());
            assertEquals(seed, rebuilt.getSeed());
        }
    }

    @Test
    void rejectsNonFinitePresentationFloatsAndFixedRanges() {
        var definition = new Id117SoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());

        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(SoundEvents.GENERIC_EXPLODE, SoundSource.MASTER,
                                    0, 0, 0, invalid, 1.0F, 0L),
                            descriptor(), codec, rewriter()));
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(SoundEvents.GENERIC_EXPLODE, SoundSource.MASTER,
                                    0, 0, 0, 1.0F, invalid, 0L),
                            descriptor(), codec, rewriter()));

            Holder<SoundEvent> invalidRange = Holder.direct(SoundEvent.createFixedRangeEvent(
                    Identifier.parse("replay:invalid_range"), invalid));
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(invalidRange, SoundSource.MASTER,
                                    0, 0, 0, 1.0F, 1.0F, 0L),
                            descriptor(), codec, rewriter()));
        }
    }

    @Test
    void rejectsTruncatedMalformedAndTrailingPayloads() {
        var definition = new Id117SoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        byte[] body = frame(SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
                100, -200, 300, 0.75F, 1.5F, 99L).payload();

        for (int length = 0; length < body.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(body, length);
            assertThrows(RuntimeException.class,
                    () -> definition.handlePlayback(frame(truncated), descriptor(), codec, rewriter()));
        }

        byte[] trailing = java.util.Arrays.copyOf(body, body.length + 1);
        trailing[trailing.length - 1] = 1;
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(trailing), descriptor(), codec, rewriter()));

        assertMalformed(packetBodyBytes(buffer -> VarInt.write(buffer, Integer.MAX_VALUE)), codec, definition);
        assertMalformed(packetBodyBytes(buffer -> {
            SoundEvent.STREAM_CODEC.encode(
                    new RegistryFriendlyByteBuf(buffer, registryAccess()), SoundEvents.GENERIC_EXPLODE);
            VarInt.write(buffer, Integer.MAX_VALUE);
        }), codec, definition);
    }

    @Test
    void refusesObjectOnlyRewriteWithoutExactCoordinateMetadata() {
        var definition = new Id117SoundPacketDefinition();
        ClientboundSoundPacket decoded = new ClientboundSoundPacket(
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.MASTER,
                16_777_217 / 8.0D,
                0.0D,
                0.0D,
                1.0F,
                1.0F,
                0L);

        assertThrows(IncompatibleAdapterException.class,
                () -> definition.rewrite(decoded, descriptor(), rewriter()));
    }

    @Test
    void rejectsDescriptorMismatchesBeforeRewriting() {
        var definition = new Id117SoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        RawPacketFrame frame = frame(SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
                10, 20, 30, 1.0F, 1.0F, 0L);
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID + 1, PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES));

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

    private static RawPacketFrame frame(
            Holder<SoundEvent> sound,
            SoundSource source,
            int x,
            int y,
            int z,
            float volume,
            float pitch,
            long seed) {
        ByteBuf encoded = packetBody(buffer -> {
            RegistryFriendlyByteBuf output = new RegistryFriendlyByteBuf(buffer, registryAccess());
            SoundEvent.STREAM_CODEC.encode(output, sound);
            output.writeEnum(source);
            output.writeInt(x);
            output.writeInt(y);
            output.writeInt(z);
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
            int expectedX,
            int expectedY,
            int expectedZ,
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
            assertEquals(expectedX, wire.readInt());
            assertEquals(expectedY, wire.readInt());
            assertEquals(expectedZ, wire.readInt());
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
                    Id117SoundPacketDefinitionTest.encode(
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
                    return Id117SoundPacketDefinitionTest.decode(protocol, encoded);
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
        return descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE);
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

    private static void assertMalformed(
            byte[] body,
            PacketCodec<Object> codec,
            Id117SoundPacketDefinition definition) {
        assertThrows(RuntimeException.class,
                () -> definition.handlePlayback(frame(body), descriptor(), codec, rewriter()));
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
