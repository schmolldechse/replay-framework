package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

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
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.Paper26_3CaptureTestState;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.VecDelta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id054MoveEntityPosPacketDefinitionTest {

    private static final int PACKET_ID = 54;
    private static final String TYPE_NAME = "play.clientbound.minecraft:move_entity_pos";
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
    void classifiesRelativeMovementAsEphemeralIdentityBearingTimelineData() {
        var definition = new Id054MoveEntityPosPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x36, Id054MoveEntityPosPacketDefinition.PACKET_ID);
        assertEquals("minecraft:move_entity_pos", Id054MoveEntityPosPacketDefinition.IDENTIFIER);
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
    void captureContextAppliesRelativePositionToTheConnectionBaseline() {
        var definition = new Id054MoveEntityPosPacketDefinition();
        var captureState = Paper26_3CaptureTestState.create();
        UUID connectionId = UUID.randomUUID();
        Key world = Key.key("minecraft:overworld");
        captureState.world(connectionId, world.toString());
        captureState.entityPosition(connectionId, 300, world.toString(), 5.25, 63.75, -0.5);
        ClientboundMoveEntityPacket.Pos packet = packet(
                300, (short) 4096, (short) -2048, (short) 2048, true);

        var context = definition.captureContext(
                packet, descriptor(), connectionId, captureState, 17L);

        assertEquals(java.util.Optional.of(world), context.world());
        assertEquals(java.util.Optional.of(new BlockPosition(6, 63, 0)), context.position());
        var position = captureState.entityPosition(connectionId, 300).orElseThrow();
        assertEquals(6.25, position.x());
        assertEquals(63.25, position.y());
        assertEquals(0.0, position.z());
        assertFalse(definition.suppressesRecipientState(packet, captureState, 17L));
    }

    @Test
    void steppedDeltaIsPreservedAndCaptureMovesByTheSumOfItsSteps() {
        var definition = new Id054MoveEntityPosPacketDefinition();
        var captureState = Paper26_3CaptureTestState.create();
        UUID connectionId = UUID.randomUUID();
        Key world = Key.key("minecraft:overworld");
        captureState.world(connectionId, world.toString());
        captureState.entityPosition(connectionId, 300, world.toString(), 0.0, 64.0, 0.0);
        VecDelta.Stepped stepped = new VecDelta.Stepped(java.util.List.of(
                new VecDelta.Stepped.DeltaStep((short) 4096, (short) 0, (short) -2048, 1),
                new VecDelta.Stepped.DeltaStep((short) 2048, (short) -4096, (short) 0, 2)));
        var packet = new ClientboundMoveEntityPacket.Pos(300, stepped, false);

        definition.captureContext(packet, descriptor(), connectionId, captureState, 3L);
        var position = captureState.entityPosition(connectionId, 300).orElseThrow();
        assertEquals(1.5, position.x());
        assertEquals(63.0, position.y());
        assertEquals(-0.5, position.z());

        var context = rewriter();
        var rewritten = definition.rewrite(definition.read(packet), context).value().orElseThrow();
        assertEquals(stepped, rewritten.delta());
        assertEquals(stepped, definition.rebuild(rewritten).getPositionDelta());
        assertEquals(context.mapEntityId(300), rewritten.entityId());
    }

    @Test
    void registersExactlyOnceInEntityDomainCatalogAndPaperRegistry() {
        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_3ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id054MoveEntityPosPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id054MoveEntityPosPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_3PacketRegistry.discover()
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
    void rewritesOnlyEntityIdAndPreservesSignedDeltasAndBothGroundStates() {
        var definition = new Id054MoveEntityPosPacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(300);
        short[][] deltas = {
            {Short.MIN_VALUE, (short) 0, Short.MAX_VALUE},
            {(short) -1, (short) 1, (short) -4096},
            {(short) 4096, Short.MAX_VALUE, Short.MIN_VALUE}
        };

        for (boolean onGround : new boolean[] {false, true}) {
            for (short[] delta : deltas) {
                var original = packet(300, delta[0], delta[1], delta[2], onGround);
                Id054MoveEntityPosPacketDefinition.Value read = definition.read(original);
                assertEquals(300, read.entityId());
                assertEquals(delta[0], linear(read.delta()).xa());
                assertEquals(delta[1], linear(read.delta()).ya());
                assertEquals(delta[2], linear(read.delta()).za());
                assertEquals(onGround, read.onGround());

                Id054MoveEntityPosPacketDefinition.Value rewritten = definition
                        .rewrite(read, context)
                        .value()
                        .orElseThrow();
                assertEquals(mappedEntityId, rewritten.entityId());
                assertEquals(delta[0], linear(rewritten.delta()).xa());
                assertEquals(delta[1], linear(rewritten.delta()).ya());
                assertEquals(delta[2], linear(rewritten.delta()).za());
                assertEquals(onGround, rewritten.onGround());

                ClientboundMoveEntityPacket.Pos rebuilt = definition.rebuild(rewritten);
                assertEquals(delta[0], linear(rebuilt.getPositionDelta()).xa());
                assertEquals(delta[1], linear(rebuilt.getPositionDelta()).ya());
                assertEquals(delta[2], linear(rebuilt.getPositionDelta()).za());
                assertEquals(onGround, rebuilt.isOnGround());
                assertEquals(mappedEntityId, readEntityId(rebuilt));
            }
        }

        assertNotEquals(300, mappedEntityId);
    }

    @Test
    void playbackRewritesAndEncodesTheRebuiltMovementPacket() {
        var definition = new Id054MoveEntityPosPacketDefinition();
        var context = rewriter();
        var original = packet(401, Short.MIN_VALUE, (short) 1234, Short.MAX_VALUE, true);
        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        ClientboundMoveEntityPacket.Pos rewritten = assertInstanceOf(
                ClientboundMoveEntityPacket.Pos.class, result.packet().orElseThrow());
        assertEquals(context.mapEntityId(401), readEntityId(rewritten));
        assertEquals(Short.MIN_VALUE, linear(rewritten.getPositionDelta()).xa());
        assertEquals((short) 1234, linear(rewritten.getPositionDelta()).ya());
        assertEquals(Short.MAX_VALUE, linear(rewritten.getPositionDelta()).za());
        assertTrue(rewritten.isOnGround());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec(rewritten)).packetId());
    }

    @Test
    void validatesDescriptorBeforePlaybackAndEncode() {
        var definition = new Id054MoveEntityPosPacketDefinition();
        ClientboundMoveEntityPacket.Pos original = packet(402, (short) 1, (short) 2, (short) 3, false);

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec(original), rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID + 1, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(),
                descriptor(PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE),
                codec(original),
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                original,
                descriptor(PACKET_ID, false, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                codec(original)));
    }

    @Test
    void nativeProtocolUsesIdFiftyFourAndPacksGroundFlagAndStepCount() {
        ProtocolInfo<?> protocol = protocol();
        short[][] deltas = {
            {Short.MIN_VALUE, (short) 0, Short.MAX_VALUE},
            {(short) -1, (short) 1, (short) -4096},
            {(short) 4096, Short.MAX_VALUE, Short.MIN_VALUE}
        };

        for (boolean onGround : new boolean[] {false, true}) {
            for (short[] delta : deltas) {
                ClientboundMoveEntityPacket.Pos expected =
                        packet(300, delta[0], delta[1], delta[2], onGround);
                ByteBuf encoded = encodePacket(protocol, expected);
                try {
                    ByteBuf wire = encoded.duplicate();
                    assertEquals(PACKET_ID, readVarInt(wire));
                    assertEquals(300, readVarInt(wire));
                    // Paper 26.3 packs the ground flag and the step count into one VarInt.
                    int stepCount = expected.getPositionDelta().stepCount();
                    assertEquals((onGround ? 1 : 0) | (stepCount << 1), readVarInt(wire));

                    ClientboundMoveEntityPacket.Pos decoded = assertInstanceOf(
                            ClientboundMoveEntityPacket.Pos.class, decode(protocol, encoded));
                    assertEquals(300, readEntityId(decoded));
                    assertEquals(delta[0], linear(decoded.getPositionDelta()).xa());
                    assertEquals(delta[1], linear(decoded.getPositionDelta()).ya());
                    assertEquals(delta[2], linear(decoded.getPositionDelta()).za());
                    assertEquals(onGround, decoded.isOnGround());
                    assertFalse(encoded.isReadable());
                } finally {
                    encoded.release();
                }
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);

        ByteBuf complete = encodePacket(protocol, packet(300, (short) 1, (short) 2, (short) 3, true));
        try {
            ByteBuf wire = complete.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            byte[] payload = new byte[wire.readableBytes()];
            wire.readBytes(payload);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
            }
        } finally {
            complete.release();
        }
    }

    private static ClientboundMoveEntityPacket.Pos packet(
            int entityId, short deltaX, short deltaY, short deltaZ, boolean onGround) {
        return new ClientboundMoveEntityPacket.Pos(
                entityId, new VecDelta.Linear(deltaX, deltaY, deltaZ), onGround);
    }

    private static VecDelta.Linear linear(VecDelta delta) {
        return assertInstanceOf(VecDelta.Linear.class, delta);
    }

    private static int readEntityId(ClientboundMoveEntityPacket.Pos packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ClientboundMoveEntityPacket.Pos.STREAM_CODEC.encode(new FriendlyByteBuf(buffer), packet);
            return readVarInt(buffer);
        } finally {
            buffer.release();
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

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(
                PACKET_ID,
                true,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
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
                PacketDisposition.EPHEMERAL,
                true,
                replayable,
                false,
                "paper-26.3/" + TYPE_NAME,
                scope);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(
                0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundMoveEntityPacket.Pos decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundMoveEntityPacket.Pos.class, packet);
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
