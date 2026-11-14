package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id119StopSoundPacketDefinitionTest {

    private static final int PACKET_ID = 119;
    private static final String TYPE_NAME = "play.clientbound.minecraft:stop_sound";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Identifier CUSTOM_SOUND = Identifier.parse("replay:music/custom_track");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesStopSoundAsAnEphemeralSafeAudioCommand() {
        var definition = new Id119StopSoundPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x77, Id119StopSoundPacketDefinition.PACKET_ID);
        assertEquals("minecraft:stop_sound", Id119StopSoundPacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id119StopSoundPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id119StopSoundPacketDefinition.class, catalogMatches.getFirst());

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
    void preservesAllFourOptionalFieldCombinationsAndTheirExactWireFlags() {
        var definition = new Id119StopSoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        List<StopSoundCase> cases = List.of(
                new StopSoundCase(null, null, 0),
                new StopSoundCase(SoundSource.AMBIENT, null, 1),
                new StopSoundCase(null, CUSTOM_SOUND, 2),
                new StopSoundCase(SoundSource.VOICE, CUSTOM_SOUND, 3));

        for (StopSoundCase testCase : cases) {
            ClientboundStopSoundPacket rewritten = rewrite(definition, codec, testCase);
            assertEquals(testCase.source(), rewritten.getSource());
            assertEquals(testCase.sound(), rewritten.getName());

            PacketCodec.EncodedPacket output = definition.encode(rewritten, descriptor(), codec);
            assertEquals(PacketPhase.PLAY, output.phase());
            assertEquals(PacketDescriptor.Direction.CLIENTBOUND, output.direction());
            assertEquals(PACKET_ID, output.packetId());
            assertOutputBody(output.payload(), testCase);
        }
    }

    @Test
    void preservesEverySoundSourceCategoryForCategoryAndFilteredStops() {
        var definition = new Id119StopSoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());

        for (SoundSource source : SoundSource.values()) {
            for (Identifier sound : new Identifier[] {null, CUSTOM_SOUND}) {
                int flags = 1 | (sound == null ? 0 : 2);
                StopSoundCase testCase = new StopSoundCase(source, sound, flags);
                ClientboundStopSoundPacket rewritten = rewrite(definition, codec, testCase);
                assertEquals(source, rewritten.getSource());
                assertEquals(sound, rewritten.getName());
                assertOutputBody(definition.encode(rewritten, descriptor(), codec).payload(), testCase);
            }
        }
    }

    @Test
    void rejectsReservedFlagsInvalidCategoriesTruncatedFieldsAndTrailingBytes() {
        var definition = new Id119StopSoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        byte[] body = body(SoundSource.BLOCKS, CUSTOM_SOUND);

        for (int length = 0; length < body.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(body, length);
            assertThrows(RuntimeException.class,
                    () -> definition.handlePlayback(frame(truncated), descriptor(), codec, rewriter()));
        }

        byte[] trailing = java.util.Arrays.copyOf(body, body.length + 1);
        trailing[trailing.length - 1] = 0;
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(trailing), descriptor(), codec, rewriter()));

        for (int flags : new int[] {0x04, 0x80, 0x83}) {
            byte[] reserved = bodyWithFlags(flags,
                    (flags & 1) != 0 ? SoundSource.BLOCKS : null,
                    (flags & 2) != 0 ? CUSTOM_SOUND : null);
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(reserved), descriptor(), codec, rewriter()));
        }

        assertThrows(RuntimeException.class,
                () -> definition.handlePlayback(
                        frame(bodyWithRawSource(1, Integer.MAX_VALUE)),
                        descriptor(), codec, rewriter()));
        assertThrows(RuntimeException.class,
                () -> definition.handlePlayback(
                        frame(bodyWithInvalidIdentifier()), descriptor(), codec, rewriter()));
    }

    @Test
    void rejectsDescriptorMismatchesBeforeReadingPayload() {
        var definition = new Id119StopSoundPacketDefinition();
        PacketCodec<Object> codec = nativePacketCodec(protocol());
        RawPacketFrame frame = frame(body(SoundSource.MUSIC, CUSTOM_SOUND));
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID + 1, PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame, mismatch, codec, rewriter()));
        }
    }

    private static ClientboundStopSoundPacket rewrite(
            Id119StopSoundPacketDefinition definition,
            PacketCodec<Object> codec,
            StopSoundCase testCase) {
        PacketRewriteResult result = definition.handlePlayback(
                frame(body(testCase.source(), testCase.sound())), descriptor(), codec, rewriter());
        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        return assertInstanceOf(ClientboundStopSoundPacket.class, result.packet().orElseThrow());
    }

    private static byte[] body(SoundSource source, Identifier sound) {
        return bodyWithFlags((source == null ? 0 : 1) | (sound == null ? 0 : 2), source, sound);
    }

    private static byte[] bodyWithFlags(int flags, SoundSource source, Identifier sound) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            FriendlyByteBuf output = new FriendlyByteBuf(buffer);
            output.writeByte(flags);
            if ((flags & 1) != 0) {
                output.writeEnum(source);
            }
            if ((flags & 2) != 0) {
                output.writeIdentifier(sound);
            }
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] bodyWithRawSource(int flags, int source) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeByte(flags);
            VarInt.write(buffer, source);
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte[] bodyWithInvalidIdentifier() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            buffer.writeByte(2);
            FriendlyByteBuf output = new FriendlyByteBuf(buffer);
            output.writeUtf("not a valid identifier");
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static void assertOutputBody(byte[] payload, StopSoundCase expected) {
        ByteBuf source = Unpooled.wrappedBuffer(payload);
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(source);
            int flags = wire.readUnsignedByte();
            assertEquals(expected.flags(), flags);
            SoundSource actualSource = (flags & 1) != 0
                    ? wire.readEnum(SoundSource.class)
                    : null;
            Identifier actualSound = (flags & 2) != 0 ? wire.readIdentifier() : null;
            assertEquals(expected.source(), actualSource);
            assertEquals(expected.sound(), actualSound);
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
                    Id119StopSoundPacketDefinitionTest.encode(
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
                    return Id119StopSoundPacketDefinitionTest.decode(protocol, encoded);
                } finally {
                    encoded.release();
                }
            }
        };
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

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private record StopSoundCase(SoundSource source, Identifier sound, int flags) {}
}
