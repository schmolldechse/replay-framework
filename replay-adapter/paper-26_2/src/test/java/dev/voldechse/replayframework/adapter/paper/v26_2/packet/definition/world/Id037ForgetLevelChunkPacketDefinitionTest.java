package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id037ForgetLevelChunkPacketDefinitionTest {

    private static final int PACKET_ID = 37;
    private static final String TYPE_NAME = "play.clientbound.minecraft:forget_level_chunk";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<ChunkPos> COORDINATES = List.of(
            new ChunkPos(0, 0),
            new ChunkPos(1, 2),
            new ChunkPos(-1, -2),
            new ChunkPos(Integer.MIN_VALUE, Integer.MAX_VALUE),
            new ChunkPos(Integer.MAX_VALUE, Integer.MIN_VALUE));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesChunkUnloadAsCheckpointRelevantSafeState() {
        var definition = new Id037ForgetLevelChunkPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x25, Id037ForgetLevelChunkPacketDefinition.PACKET_ID);
        assertEquals("minecraft:forget_level_chunk", Id037ForgetLevelChunkPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
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
        assertInstanceOf(Id037ForgetLevelChunkPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id037ForgetLevelChunkPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.CHUNK_LIGHT,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));
    }

    @Test
    void rewritePreservesAllSignedChunkCoordinateValues() {
        var definition = new Id037ForgetLevelChunkPacketDefinition();

        for (ChunkPos position : COORDINATES) {
            ClientboundForgetLevelChunkPacket original =
                    new ClientboundForgetLevelChunkPacket(position);
            PacketRewriteResult result = definition.rewrite(original, descriptor(), rewriter());
            ClientboundForgetLevelChunkPacket rebuilt = assertInstanceOf(
                    ClientboundForgetLevelChunkPacket.class, result.packet().orElseThrow());

            assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
            assertNotSame(original, rebuilt);
            assertEquals(position, rebuilt.pos());
        }
    }

    @Test
    void playbackRebuildsAndEncodesUnloadWithoutConsultingLiveChunkState() {
        var definition = new Id037ForgetLevelChunkPacketDefinition();
        ClientboundForgetLevelChunkPacket original =
                new ClientboundForgetLevelChunkPacket(new ChunkPos(-129, 257));
        PacketCodec<Object> codec = codec(original);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec, rewriter());
        ClientboundForgetLevelChunkPacket rebuilt = assertInstanceOf(
                ClientboundForgetLevelChunkPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(original.pos(), rebuilt.pos());
        assertEquals(PACKET_ID, definition.encode(rebuilt, descriptor(), codec).packetId());
    }

    @Test
    void nativeProtocolEncodesIdAndPackedSignedChunkCoordinates() {
        ProtocolInfo<?> protocol = protocol();

        for (ChunkPos position : COORDINATES) {
            ClientboundForgetLevelChunkPacket original =
                    new ClientboundForgetLevelChunkPacket(position);
            ByteBuf encoded = encodePacket(protocol, original);
            try {
                ByteBuf wire = encoded.duplicate();
                assertEquals(PACKET_ID, wire.readUnsignedByte());
                long expectedPackedPosition = ((long) position.z() << 32)
                        | (position.x() & 0xFFFF_FFFFL);
                assertEquals(expectedPackedPosition, wire.readLong());
                assertFalse(wire.isReadable());

                ClientboundForgetLevelChunkPacket decoded = assertInstanceOf(
                        ClientboundForgetLevelChunkPacket.class,
                        decode(protocol, encoded.duplicate()));
                assertEquals(position, decoded.pos());
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPackedPositionPrefix() {
        ProtocolInfo<?> protocol = protocol();

        for (int payloadLength = 0; payloadLength < Long.BYTES; payloadLength++) {
            ByteBuf truncated = Unpooled.buffer();
            try {
                truncated.writeByte(PACKET_ID);
                truncated.writeZero(payloadLength);
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(
            ProtocolInfo<?> protocol,
            ByteBuf buffer,
            Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> codec(ClientboundForgetLevelChunkPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundForgetLevelChunkPacket.class, packet);
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
}
