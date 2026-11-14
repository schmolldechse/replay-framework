package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
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
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id113SetTimePacketDefinitionTest {

    private static final int PACKET_ID = 113;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_time";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static MappedRegistry<WorldClock> clocks;
    private static Holder.Reference<WorldClock> overworldClock;
    private static Holder.Reference<WorldClock> endClock;
    private static Holder.Reference<WorldClock> customClock;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        clocks = new MappedRegistry<>(Registries.WORLD_CLOCK, Lifecycle.stable(), false);
        overworldClock = registerClock("minecraft:overworld");
        endClock = registerClock("minecraft:the_end");
        customClock = registerClock("replay_test:custom_clock");
        clocks.freeze();
    }

    @Test
    void classifiesTimeAsSafeCheckpointedWorldStateAtPaper262Id() {
        var definition = new Id113SetTimePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x71, Id113SetTimePacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_time", Id113SetTimePacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        assertEquals(1, WorldPacketDefinitions.definitions().stream()
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
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeCodecPreservesGameTimeAndEmptyClockDeltasAcrossLongBoundaries() {
        ProtocolInfo<?> protocol = protocol();

        for (long gameTime : new long[] {
            Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE
        }) {
            assertNativeRoundTrip(protocol, packet(gameTime, Map.of()), true);
        }
    }

    @Test
    void nativeCodecPreservesPartialAndMultiClockUpdatesIncludingCustomRegistryEntries() {
        ProtocolInfo<?> protocol = protocol();
        Map<Holder<WorldClock>, ClockNetworkState> partial = Map.of(
                customClock, new ClockNetworkState(Long.MIN_VALUE, -0.0F, Float.NaN));
        assertNativeRoundTrip(protocol, packet(6_000_000_000L, partial), true);

        Map<Holder<WorldClock>, ClockNetworkState> multiple = new LinkedHashMap<>();
        multiple.put(overworldClock,
                new ClockNetworkState(-1L, Float.POSITIVE_INFINITY, 0.0F));
        multiple.put(endClock,
                new ClockNetworkState(Long.MAX_VALUE, 0.25F, -Float.MAX_VALUE));
        multiple.put(customClock,
                new ClockNetworkState(0L, Float.intBitsToFloat(0x7FC01234), 1000.0F));
        ClientboundSetTimePacket packet = packet(Long.MAX_VALUE, multiple);
        assertNativeRoundTrip(protocol, packet, false);

        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ClockNetworkState customState = decode(protocol, encoded.duplicate())
                    .clockUpdates()
                    .get(customClock);
            assertEquals(0x7FC01234, Float.floatToRawIntBits(customState.partialTick()));
            assertEquals(Float.floatToRawIntBits(1000.0F),
                    Float.floatToRawIntBits(customState.rate()));
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecWritesGameTimeThenRegistryKeyedClockStateFields() {
        ProtocolInfo<?> protocol = protocol();
        ClockNetworkState state = new ClockNetworkState(300L, 0.25F, 1.5F);
        ClientboundSetTimePacket packet = packet(
                0x0102030405060708L, Map.of(endClock, state));
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, wire.readVarInt());
            assertEquals(packet.gameTime(), wire.readLong());
            assertEquals(1, wire.readVarInt());
            assertEquals(clocks.getId(endClock.value()), wire.readVarInt());
            assertEquals(state.totalTicks(), wire.readVarLong());
            assertEquals(Float.floatToRawIntBits(state.partialTick()),
                    Float.floatToRawIntBits(wire.readFloat()));
            assertEquals(Float.floatToRawIntBits(state.rate()),
                    Float.floatToRawIntBits(wire.readFloat()));
            assertFalse(wire.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void definitionCopiesClockDeltaAndExposesImmutableReplayState() {
        var definition = new Id113SetTimePacketDefinition();
        Map<Holder<WorldClock>, ClockNetworkState> updates = new HashMap<>();
        ClockNetworkState state = new ClockNetworkState(42L, 0.5F, 2.0F);
        updates.put(customClock, state);
        ClientboundSetTimePacket original = packet(7L, updates);

        Id113SetTimePacketDefinition.Value value = definition.read(original);
        updates.clear();

        assertEquals(7L, value.gameTime());
        assertEquals(Map.of(customClock, state), value.clockUpdates());
        assertThrows(UnsupportedOperationException.class,
                () -> value.clockUpdates().put(endClock, state));
        assertEquals(Map.of(customClock, state), definition.rebuild(value).clockUpdates());
    }

    @Test
    void checkpointEncoderRetainsTimePacketAsGlobalState() {
        Map<Holder<WorldClock>, ClockNetworkState> updates = Map.of(
                overworldClock, new ClockNetworkState(24_000L, 0.0F, 1.0F),
                customClock, new ClockNetworkState(900L, 0.5F, 0.25F));
        ClientboundSetTimePacket packet = packet(700_000L, updates);
        var blueprint = new Paper26_2CheckpointEncoder.PacketBlueprint(
                TYPE_NAME,
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                "minecraft:world",
                "time",
                packet);
        var encoder = new Paper26_2CheckpointEncoder(
                Paper26_2PacketRegistry.discover(),
                ignored -> CompletableFuture.completedFuture(
                        new Paper26_2CheckpointEncoder.CheckpointSnapshot(List.of(blueprint))),
                candidate -> new Paper26_2CheckpointEncoder.EncodedPacket(
                        candidate.descriptorTypeName(),
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        candidate.family(),
                        candidate.worldKey(),
                        candidate.targetKey(),
                        nativePayload((ClientboundSetTimePacket) candidate.nativePacket())),
                Runnable::run);

        var checkpoint = encoder.encode(new CheckpointEncoder.CheckpointRequest(
                        RecordingScope.builder().build(),
                        0L,
                        0L,
                        CheckpointEncoder.CheckpointKind.INITIAL))
                .toCompletableFuture()
                .join();

        assertEquals(1, checkpoint.initializationFrames().size());
        RawPacketFrame frame = checkpoint.initializationFrames().getFirst();
        assertEquals(PACKET_ID, frame.packetId());
        ClientboundSetTimePacket restored = decode(protocol(), fullWire(frame.payload()));
        assertEquals(packet.gameTime(), restored.gameTime());
        assertEquals(packet.clockUpdates(), restored.clockUpdates());
    }

    @Test
    void playbackPreservesGameTimeAndEveryClockUpdate() {
        var definition = new Id113SetTimePacketDefinition();
        PacketCodec<Object> codec = codec();
        Map<Holder<WorldClock>, ClockNetworkState> updates = Map.of(
                overworldClock, new ClockNetworkState(20_000L, 0.25F, 1.0F),
                customClock, new ClockNetworkState(91L, -0.0F, 2.0F));
        ClientboundSetTimePacket original = packet(900_000L, updates);
        PacketRewriteResult result = definition.handlePlayback(
                frame(nativePayload(original)), descriptor(), codec, rewriter());

        ClientboundSetTimePacket rebuilt = assertInstanceOf(
                ClientboundSetTimePacket.class, result.packet().orElseThrow());
        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(original.gameTime(), rebuilt.gameTime());
        assertEquals(original.clockUpdates(), rebuilt.clockUpdates());

        var encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        ClientboundSetTimePacket decodedAgain = decode(protocol(), fullWire(encoded.payload()));
        assertEquals(original.gameTime(), decodedAgain.gameTime());
        assertEquals(original.clockUpdates(), decodedAgain.clockUpdates());
    }

    @Test
    void nativeDecoderRejectsTruncatedPacketBodiesMalformedVarIntsAndUnknownClocks() {
        ProtocolInfo<?> protocol = protocol();
        byte[] body = nativePayload(packet(
                123L, Map.of(customClock, new ClockNetworkState(456L, 0.5F, 1.5F))));
        for (int length = 0; length < body.length; length++) {
            int truncatedLength = length;
            assertNativeDecodeFails(protocol,
                    input -> input.writeBytes(body, 0, truncatedLength));
        }

        assertNativeDecodeFails(protocol, input -> {
            input.writeLong(0L);
            for (int index = 0; index < 6; index++) {
                input.writeByte(0x80);
            }
        });

        assertNativeDecodeFails(protocol, input -> {
            input.writeLong(0L);
            VarInt.write(input, 1);
            VarInt.write(input, 99);
        });

        assertNativeDecodeFails(protocol, input -> {
            input.writeLong(0L);
            VarInt.write(input, 1);
            VarInt.write(input, clocks.getId(customClock.value()));
            for (int index = 0; index < 11; index++) {
                input.writeByte(0x80);
            }
        });
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            ByteBuf encoded = encodePacket(protocol, packet(42L, Map.of()));
            try {
                wire.writeBytes(encoded);
            } finally {
                encoded.release();
            }
            wire.writeByte(0x7A);

            ClientboundSetTimePacket decoded = decode(protocol, wire);
            assertEquals(42L, decoded.gameTime());
            assertTrue(decoded.clockUpdates().isEmpty());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void rejectsDescriptorMismatchesAndNonReplayableDescriptors() {
        var definition = new Id113SetTimePacketDefinition();
        PacketCodec<Object> codec = codec();
        ClientboundSetTimePacket original = packet(12L, Map.of());

        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(new byte[0]),
                        descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                        codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(frame(nativePayload(original)),
                        descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.encode(original,
                        descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
    }

    private static Holder.Reference<WorldClock> registerClock(String identifier) {
        ResourceKey<WorldClock> key = ResourceKey.create(
                Registries.WORLD_CLOCK, Identifier.parse(identifier));
        return clocks.register(key, new WorldClock(), RegistrationInfo.BUILT_IN);
    }

    private static ClientboundSetTimePacket packet(
            long gameTime, Map<Holder<WorldClock>, ClockNetworkState> updates) {
        return new ClientboundSetTimePacket(gameTime, updates);
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundSetTimePacket expected,
            boolean requireExactWire) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf header = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, header.readVarInt());

            ClientboundSetTimePacket decoded = decode(protocol, encoded);
            assertEquals(expected.gameTime(), decoded.gameTime());
            assertEquals(expected.clockUpdates(), decoded.clockUpdates());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                if (requireExactWire) {
                    assertArrayEquals(originalWire, bytes(reencoded));
                }
                ClientboundSetTimePacket decodedAgain = decode(protocol, reencoded);
                assertEquals(expected.gameTime(), decodedAgain.gameTime());
                assertEquals(expected.clockUpdates(), decodedAgain.clockUpdates());
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static byte[] nativePayload(ClientboundSetTimePacket packet) {
        ByteBuf wire = encodePacket(protocol(), packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(wire));
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static byte[] fullWire(byte[] payload) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(payload);
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static PacketCodec<Object> codec() {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetTimePacket nativePacket = assertInstanceOf(
                        ClientboundSetTimePacket.class, packet);
                ByteBuf wire = encodePacket(protocol(), nativePacket);
                try {
                    assertEquals(PACKET_ID, VarInt.read(wire));
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(wire));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                return Id113SetTimePacketDefinitionTest.decode(
                        protocol(), fullWire(frame.payload()));
            }
        };
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

    private static ClientboundSetTimePacket decode(ProtocolInfo<?> protocol, byte[] wire) {
        ByteBuf buffer = Unpooled.wrappedBuffer(wire);
        try {
            return decode(protocol, buffer);
        } finally {
            buffer.release();
        }
    }

    private static ClientboundSetTimePacket decode(ProtocolInfo<?> protocol, ByteBuf wire) {
        return assertInstanceOf(ClientboundSetTimePacket.class, decodePacket(protocol, wire));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decodePacket(ProtocolInfo<?> protocol, ByteBuf wire) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(wire);
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            java.util.function.Consumer<ByteBuf> bodyWriter) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            bodyWriter.accept(wire);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registries = new RegistryAccess.ImmutableRegistryAccess(List.of(clocks));
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registries));
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
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }
}
