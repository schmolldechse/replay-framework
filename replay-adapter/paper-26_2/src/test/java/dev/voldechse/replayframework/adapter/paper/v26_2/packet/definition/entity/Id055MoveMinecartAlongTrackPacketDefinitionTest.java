package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

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
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior.MinecartStep;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id055MoveMinecartAlongTrackPacketDefinitionTest {

    private static final int PACKET_ID = 55;
    private static final String TYPE_NAME =
            "play.clientbound.minecraft:move_minecart_along_track";
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
    void classifiesMinecartTrajectoryAsStatefulReplayableIdentityBearingCheckpointData() {
        var definition = new Id055MoveMinecartAlongTrackPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x37, Id055MoveMinecartAlongTrackPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:move_minecart_along_track",
                Id055MoveMinecartAlongTrackPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInEntityDomainCatalogAndPaperRegistry() {
        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(
                Id055MoveMinecartAlongTrackPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(
                Id055MoveMinecartAlongTrackPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registryDescriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, registryDescriptor.disposition());
        assertTrue(registryDescriptor.captureByDefault());
        assertTrue(registryDescriptor.replayable());
        assertTrue(registryDescriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                registryDescriptor.playbackScope());
    }

    @Test
    void rewritesOnlyMinecartIdAndPreservesTheOrderedStepSequence() {
        var definition = new Id055MoveMinecartAlongTrackPacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(300);
        MinecartStep first = step(
                new Vec3(1.25, -2.5, 3.75),
                new Vec3(-0.125, 0.25, -0.5),
                90.0F,
                -90.0F,
                0.5F);
        MinecartStep second = step(
                new Vec3(-11.0, 0.5, 1024.0),
                new Vec3(0.0, -4.0, 8.0),
                180.0F,
                45.0F,
                1.25F);
        List<MinecartStep> originalSteps = List.of(first, second, first);
        var original = packet(300, originalSteps);

        var read = definition.read(original);
        assertEquals(300, read.entityId());
        assertEquals(originalSteps, read.lerpSteps());
        assertEquals(first, read.lerpSteps().getFirst());
        assertEquals(second, read.lerpSteps().get(1));
        assertEquals(first, read.lerpSteps().get(2));

        var rewritten = definition.rewrite(read, context).value().orElseThrow();
        assertEquals(mappedEntityId, rewritten.entityId());
        assertEquals(originalSteps, rewritten.lerpSteps());
        assertEquals(
                new ClientboundMoveMinecartPacket(mappedEntityId, originalSteps),
                definition.rebuild(rewritten));
        assertNotEquals(300, mappedEntityId);
    }

    @Test
    void supportsEmptyListsAndDefensivelyCopiesStepCollections() {
        var definition = new Id055MoveMinecartAlongTrackPacketDefinition();
        assertTrue(definition.read(packet(300, List.of())).lerpSteps().isEmpty());

        ProtocolInfo<?> protocol = protocol();
        ByteBuf emptyWire = encodePacket(protocol, packet(300, List.of()));
        try {
            ClientboundMoveMinecartPacket decoded = assertInstanceOf(
                    ClientboundMoveMinecartPacket.class, decode(protocol, emptyWire));
            assertTrue(definition.read(decoded).lerpSteps().isEmpty());
        } finally {
            emptyWire.release();
        }

        var source = new ArrayList<>(List.of(step(
                new Vec3(1.0, 2.0, 3.0),
                new Vec3(4.0, 5.0, 6.0),
                0.0F,
                0.0F,
                0.0F)));
        var value = new Id055MoveMinecartAlongTrackPacketDefinition.Value(300, source);
        source.clear();

        assertEquals(1, value.lerpSteps().size());
        assertThrows(UnsupportedOperationException.class, () -> value.lerpSteps().clear());
        assertThrows(NullPointerException.class, () ->
                new Id055MoveMinecartAlongTrackPacketDefinition.Value(300, null));
        assertThrows(NullPointerException.class, () ->
                new Id055MoveMinecartAlongTrackPacketDefinition.Value(
                        300, Arrays.asList(value.lerpSteps().getFirst(), null)));
    }

    @Test
    void playbackRewritesTheEntityAndEncodesTheRebuiltTrajectoryPacket() {
        var definition = new Id055MoveMinecartAlongTrackPacketDefinition();
        var context = rewriter();
        var original = packet(
                401,
                List.of(step(
                        new Vec3(1.0, 2.0, 3.0),
                        new Vec3(0.25, -0.5, 0.75),
                        135.0F,
                        -45.0F,
                        0.75F)));

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundMoveMinecartPacket rewritten = assertInstanceOf(
                ClientboundMoveMinecartPacket.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(401), rewritten.entityId());
        assertEquals(original.lerpSteps(), rewritten.lerpSteps());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec(rewritten)).packetId());
    }

    @Test
    void nativeProtocolUsesIdFiftyFiveAndEncodesTheCompleteStepSequenceInOrder() {
        ProtocolInfo<?> protocol = protocol();
        MinecartStep first = step(
                new Vec3(1.25, -2.5, 3.75),
                new Vec3(-0.125, 0.25, -0.5),
                90.0F,
                -90.0F,
                0.5F);
        MinecartStep second = step(
                new Vec3(-11.0, 0.5, 1024.0),
                new Vec3(0.0, -4.0, 8.0),
                180.0F,
                45.0F,
                1.25F);
        ClientboundMoveMinecartPacket expected = packet(300, List.of(first, second));
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytesOf(encoded);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(300, readVarInt(wire));
            assertEquals(2, readVarInt(wire));
            assertStepWireFields(wire, first, (byte) 64, (byte) -64);
            assertStepWireFields(wire, second, (byte) -128, (byte) 32);
            assertFalse(wire.isReadable());

            ClientboundMoveMinecartPacket decoded = assertInstanceOf(
                    ClientboundMoveMinecartPacket.class, decode(protocol, encoded));
            assertEquals(expected.entityId(), decoded.entityId());
            var value = new Id055MoveMinecartAlongTrackPacketDefinition().read(decoded);
            ClientboundMoveMinecartPacket rebuilt =
                    new Id055MoveMinecartAlongTrackPacketDefinition().rebuild(value);
            assertEquals(decoded.lerpSteps(), rebuilt.lerpSteps());
            ByteBuf rebuiltEncoded = encodePacket(protocol, rebuilt);
            try {
                assertArrayEquals(expectedWire, bytesOf(rebuiltEncoded));
            } finally {
                rebuiltEncoded.release();
            }
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeRotationByteValuesSurviveIdentityRewriteAndRebuild() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id055MoveMinecartAlongTrackPacketDefinition();
        var context = rewriter();

        for (int rawRotation = Byte.MIN_VALUE; rawRotation <= Byte.MAX_VALUE; rawRotation++) {
            ByteBuf original = packetWithRawRotations((byte) rawRotation, (byte) ~rawRotation);
            try {
                byte[] originalBytes = bytesOf(original);
                ClientboundMoveMinecartPacket decoded = assertInstanceOf(
                        ClientboundMoveMinecartPacket.class, decode(protocol, original));
                var value = definition.rewrite(definition.read(decoded), context)
                        .value()
                        .orElseThrow();
                ClientboundMoveMinecartPacket rebuilt = definition.rebuild(value);
                assertEquals(context.mapEntityId(300), rebuilt.entityId());
                ByteBuf encoded = encodePacket(protocol, rebuilt);
                try {
                    assertArrayEquals(stepPayload(originalBytes), stepPayload(bytesOf(encoded)),
                            "Rotation byte changed for raw value " + rawRotation);
                } finally {
                    encoded.release();
                }
            } finally {
                original.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);

        ByteBuf complete = encodePacket(
                protocol,
                packet(300, List.of(step(
                        new Vec3(1.0, 2.0, 3.0),
                        new Vec3(4.0, 5.0, 6.0),
                        90.0F,
                        -90.0F,
                        0.5F))));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] payload = new byte[wire.readableBytes()];
            wire.readBytes(payload);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, Arrays.copyOf(payload, length));
            }
        } finally {
            complete.release();
        }
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncode() {
        var definition = new Id055MoveMinecartAlongTrackPacketDefinition();
        ClientboundMoveMinecartPacket original = packet(402, List.of());

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID + 1, true, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID, true, true, PacketDescriptor.PlaybackScope.SAFE),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                original,
                descriptor(PACKET_ID, false, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original)));
    }

    private static ClientboundMoveMinecartPacket packet(int entityId, List<MinecartStep> steps) {
        return new ClientboundMoveMinecartPacket(entityId, steps);
    }

    private static MinecartStep step(
            Vec3 position, Vec3 movement, float yRot, float xRot, float weight) {
        return new MinecartStep(position, movement, yRot, xRot, weight);
    }

    private static void assertStepWireFields(
            ByteBuf wire, MinecartStep step, byte expectedYRot, byte expectedXRot) {
        assertEquals(step.position().x, wire.readDouble());
        assertEquals(step.position().y, wire.readDouble());
        assertEquals(step.position().z, wire.readDouble());
        assertEquals(step.movement().x, wire.readDouble());
        assertEquals(step.movement().y, wire.readDouble());
        assertEquals(step.movement().z, wire.readDouble());
        assertEquals(expectedYRot, wire.readByte());
        assertEquals(expectedXRot, wire.readByte());
        assertEquals(Float.floatToRawIntBits(step.weight()), Float.floatToRawIntBits(wire.readFloat()));
    }

    private static ByteBuf packetWithRawRotations(byte yRot, byte xRot) {
        ByteBuf buffer = Unpooled.buffer();
        writeVarInt(buffer, PACKET_ID);
        writeVarInt(buffer, 300);
        writeVarInt(buffer, 1);
        buffer.writeLong(0x8000000000000000L);
        buffer.writeLong(0x7FF8123456789ABCL);
        buffer.writeLong(0x7FF0000000000000L);
        buffer.writeLong(0xFFF0000000000000L);
        buffer.writeLong(0x0000000000000001L);
        buffer.writeLong(0x3FF0000000000000L);
        buffer.writeByte(yRot);
        buffer.writeByte(xRot);
        buffer.writeFloat(Float.intBitsToFloat(0x7FC01234));
        return buffer;
    }

    private static byte[] bytesOf(ByteBuf buffer) {
        ByteBuf readable = buffer.duplicate();
        byte[] bytes = new byte[readable.readableBytes()];
        readable.readBytes(bytes);
        return bytes;
    }

    private static byte[] stepPayload(byte[] packetBytes) {
        ByteBuf packet = Unpooled.wrappedBuffer(packetBytes);
        try {
            assertEquals(PACKET_ID, readVarInt(packet));
            readVarInt(packet);
            byte[] payload = new byte[packet.readableBytes()];
            packet.readBytes(payload);
            return payload;
        } finally {
            packet.release();
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
        return descriptor(PACKET_ID, true, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor descriptor(
            int packetId,
            boolean replayable,
            boolean checkpointRelevant,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                checkpointRelevant,
                "paper-26.2/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(
                0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundMoveMinecartPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundMoveMinecartPacket.class, packet);
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
