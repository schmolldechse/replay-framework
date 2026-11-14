package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id111SetSimulationDistancePacketDefinitionTest {

    private static final int PACKET_ID = 111;
    private static final String TYPE_NAME =
            "play.clientbound.minecraft:set_simulation_distance";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int[] SIMULATION_DISTANCE_BOUNDARIES = {
        Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesSimulationDistanceAsSafeCheckpointedWorldState() {
        var definition = new Id111SetSimulationDistancePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x6F, Id111SetSimulationDistancePacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_simulation_distance",
                Id111SetSimulationDistancePacketDefinition.IDENTIFIER);
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
    void checkpointEncoderRetainsSimulationDistanceAsGlobalState() {
        var packet = packet(12);
        var blueprint = new Paper26_2CheckpointEncoder.PacketBlueprint(
                TYPE_NAME,
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                "minecraft:world",
                "simulation-distance",
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
                        nativePayload((ClientboundSetSimulationDistancePacket)
                                candidate.nativePacket())),
                Runnable::run);

        var checkpoint = encoder.encode(new CheckpointEncoder.CheckpointRequest(
                        RecordingScope.builder().build(),
                        0L,
                        0L,
                        CheckpointEncoder.CheckpointKind.INITIAL))
                .toCompletableFuture()
                .join();

        assertEquals(1, checkpoint.initializationFrames().size());
        assertEquals(PACKET_ID, checkpoint.initializationFrames().getFirst().packetId());
        assertArrayEquals(nativePayload(packet), checkpoint.initializationFrames().getFirst().payload());
    }

    @Test
    void nativeCodecPreservesTheCompleteSignedVarIntRange() {
        ProtocolInfo<?> protocol = protocol();

        for (int simulationDistance : SIMULATION_DISTANCE_BOUNDARIES) {
            ClientboundSetSimulationDistancePacket original = packet(simulationDistance);
            ByteBuf encoded = encodePacket(protocol, original);
            try {
                byte[] expectedWire = bytes(encoded);
                FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
                assertEquals(PACKET_ID, wire.readVarInt());
                assertEquals(simulationDistance, wire.readVarInt());
                assertFalse(wire.isReadable());

                ClientboundSetSimulationDistancePacket decoded = assertInstanceOf(
                        ClientboundSetSimulationDistancePacket.class, decode(protocol, encoded));
                assertEquals(simulationDistance, decoded.simulationDistance());

                var definition = new Id111SetSimulationDistancePacketDefinition();
                assertEquals(definition.read(original), definition.read(decoded));
                ByteBuf rebuilt = encodePacket(protocol, definition.rebuild(definition.read(decoded)));
                try {
                    assertArrayEquals(expectedWire, bytes(rebuilt));
                } finally {
                    rebuilt.release();
                }
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeCodecCanonicalizesAnOverlongSimulationDistanceVarInt() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeByte(0x80);
            wire.writeByte(0x00);

            ClientboundSetSimulationDistancePacket decoded = assertInstanceOf(
                    ClientboundSetSimulationDistancePacket.class, decode(protocol, wire));
            assertEquals(0, decoded.simulationDistance());
            ByteBuf canonical = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(packetWire(0), bytes(canonical));
            } finally {
                canonical.release();
            }
        } finally {
            wire.release();
        }
    }

    @Test
    void nativeCodecRejectsTruncatedAndOverlongSimulationDistanceVarInts() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf complete = encodePacket(protocol, packet(Integer.MAX_VALUE));
        try {
            FriendlyByteBuf parsed = new FriendlyByteBuf(complete.duplicate());
            assertEquals(PACKET_ID, parsed.readVarInt());
            byte[] body = bytes(parsed);
            assertEquals(5, body.length);

            for (int prefixLength = 0; prefixLength < body.length; prefixLength++) {
                int truncatedLength = prefixLength;
                assertNativeDecodeFails(protocol,
                        buffer -> buffer.writeBytes(body, 0, truncatedLength));
            }
        } finally {
            complete.release();
        }

        assertNativeDecodeFails(protocol, buffer -> {
            for (int index = 0; index < 6; index++) {
                buffer.writeByte(0x80);
            }
        });
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            wire.writeBytes(packetWire(23));
            wire.writeByte(0x7A);

            ClientboundSetSimulationDistancePacket decoded = assertInstanceOf(
                    ClientboundSetSimulationDistancePacket.class, decode(protocol, wire));
            assertEquals(23, decoded.simulationDistance());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void playbackPreservesTheSimulationDistanceAndNativePayload() {
        var definition = new Id111SetSimulationDistancePacketDefinition();
        PacketCodec<Object> codec = codec();

        for (int simulationDistance : new int[] {Integer.MIN_VALUE, -1, 0, 128, Integer.MAX_VALUE}) {
            ClientboundSetSimulationDistancePacket original = packet(simulationDistance);
            byte[] payload = nativePayload(original);
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            ClientboundSetSimulationDistancePacket rebuilt = assertInstanceOf(
                    ClientboundSetSimulationDistancePacket.class, result.packet().orElseThrow());

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            assertEquals(simulationDistance, rebuilt.simulationDistance());
            var encoded = definition.encode(rebuilt, descriptor(), codec);
            assertEquals(PACKET_ID, encoded.packetId());
            assertArrayEquals(payload, encoded.payload());
        }
    }

    @Test
    void rejectsDescriptorMismatchesAndNonReplayableDescriptors() {
        var definition = new Id111SetSimulationDistancePacketDefinition();
        PacketCodec<Object> codec = codec();
        ClientboundSetSimulationDistancePacket original = packet(12);

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

    private static ClientboundSetSimulationDistancePacket packet(int simulationDistance) {
        return new ClientboundSetSimulationDistancePacket(simulationDistance);
    }

    private static byte[] packetWire(int simulationDistance) {
        ByteBuf wire = encodePacket(protocol(), packet(simulationDistance));
        try {
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static byte[] nativePayload(ClientboundSetSimulationDistancePacket packet) {
        ByteBuf wire = encodePacket(protocol(), packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(wire));
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static PacketCodec<Object> codec() {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetSimulationDistancePacket nativePacket = assertInstanceOf(
                        ClientboundSetSimulationDistancePacket.class, packet);
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
                ByteBuf wire = Unpooled.buffer();
                try {
                    VarInt.write(wire, PACKET_ID);
                    wire.writeBytes(frame.payload());
                    return decodePacket(protocol(), wire);
                } finally {
                    wire.release();
                }
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

    private static ClientboundSetSimulationDistancePacket decode(
            ProtocolInfo<?> protocol, ByteBuf wire) {
        return assertInstanceOf(
                ClientboundSetSimulationDistancePacket.class, decodePacket(protocol, wire));
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
