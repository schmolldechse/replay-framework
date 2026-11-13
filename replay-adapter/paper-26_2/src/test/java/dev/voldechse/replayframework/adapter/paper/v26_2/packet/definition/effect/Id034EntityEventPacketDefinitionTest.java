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
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.EntityEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id034EntityEventPacketDefinitionTest {

    private static final int PACKET_ID = 34;
    private static final String TYPE_NAME = "play.clientbound.minecraft:entity_event";
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
    void classifiesEntityEventAsEphemeralIdentityBearingData() {
        var definition = new Id034EntityEventPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x22, Id034EntityEventPacketDefinition.PACKET_ID);
        assertEquals("minecraft:entity_event", Id034EntityEventPacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id034EntityEventPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id034EntityEventPacketDefinition.class, catalogMatches.getFirst());

        var registryDescriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registryDescriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, registryDescriptor.disposition());
        assertTrue(registryDescriptor.captureByDefault());
        assertTrue(registryDescriptor.replayable());
        assertFalse(registryDescriptor.checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                registryDescriptor.playbackScope());
    }

    @Test
    void preservesAllUnsignedStatusValuesWhileRewritingEntityIdentity() {
        var definition = new Id034EntityEventPacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(41);

        for (int eventStatus = 0; eventStatus <= 0xFF; eventStatus++) {
            Id034EntityEventPacketDefinition.Value value = definition.read(packet(41, eventStatus));
            assertEquals(41, value.entityId(), "read entity ID for status " + eventStatus);
            assertEquals(eventStatus, value.eventStatus(), "read status byte " + eventStatus);

            ValueRewriteResult<Id034EntityEventPacketDefinition.Value> rewritten =
                    definition.rewrite(value, context);
            if (isConnectionLocalStatus(eventStatus)) {
                assertEquals(
                        ValueRewriteResult.Decision.DROP_LOCAL,
                        rewritten.decision(),
                        "local status " + eventStatus);
                assertTrue(rewritten.value().isEmpty());
                continue;
            }

            assertEquals(
                    ValueRewriteResult.Decision.SEND,
                    rewritten.decision(),
                    "scene status " + eventStatus);
            Id034EntityEventPacketDefinition.Value rewrittenValue =
                    rewritten.value().orElseThrow();
            assertEquals(mappedEntityId, rewrittenValue.entityId());
            assertEquals(eventStatus, rewrittenValue.eventStatus());

            ClientboundEntityEventPacket rebuilt = definition.rebuild(rewrittenValue);
            assertEquals(mappedEntityId, entityId(rebuilt));
            assertEquals(eventStatus, rebuilt.getEventId() & 0xFF);
        }

        assertNotEquals(41, mappedEntityId);
    }

    @Test
    void dropsExactlyThePlayerDebugAndPermissionStatusIds() {
        assertEquals(
                Set.of(22, 23, 24, 25, 26, 27, 28),
                java.util.stream.IntStream.rangeClosed(0, 0xFF)
                        .filter(Id034EntityEventPacketDefinitionTest::isConnectionLocalStatus)
                        .boxed()
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(22, EntityEvent.REDUCED_DEBUG_INFO);
        assertEquals(23, EntityEvent.FULL_DEBUG_INFO);
        assertEquals(24, EntityEvent.PERMISSION_LEVEL_ALL);
        assertEquals(25, EntityEvent.PERMISSION_LEVEL_MODERATORS);
        assertEquals(26, EntityEvent.PERMISSION_LEVEL_GAMEMASTERS);
        assertEquals(27, EntityEvent.PERMISSION_LEVEL_ADMINS);
        assertEquals(28, EntityEvent.PERMISSION_LEVEL_OWNERS);
    }

    @Test
    void rejectsEventValuesOutsideTheUnsignedByteDomain() {
        assertEquals(0, new Id034EntityEventPacketDefinition.Value(1, 0).eventStatus());
        assertEquals(0xFF, new Id034EntityEventPacketDefinition.Value(1, 0xFF).eventStatus());
        assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id034EntityEventPacketDefinition.Value(1, -1));
        assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id034EntityEventPacketDefinition.Value(1, 0x100));
    }

    @Test
    void playbackRewritesSceneEventsAndDropsConnectionLocalEventsBeforeEncoding() {
        var definition = new Id034EntityEventPacketDefinition();
        var context = rewriter();
        AtomicInteger encodes = new AtomicInteger();
        AtomicInteger decodes = new AtomicInteger();

        ClientboundEntityEventPacket scenePacket = packet(51, EntityEvent.HONEY_SLIDE);
        PacketRewriteResult sceneResult = definition.handlePlayback(
                frame(), descriptor(), codec(scenePacket, encodes, decodes), context);
        ClientboundEntityEventPacket rewritten = assertInstanceOf(
                ClientboundEntityEventPacket.class, sceneResult.packet().orElseThrow());
        assertEquals(PacketRewriteResult.Decision.SEND, sceneResult.decision());
        assertEquals(context.mapEntityId(51), entityId(rewritten));
        assertEquals(EntityEvent.HONEY_SLIDE, rewritten.getEventId());
        assertEquals(
                PACKET_ID,
                definition.encode(rewritten, descriptor(), codec(rewritten, encodes, decodes))
                        .packetId());

        ClientboundEntityEventPacket localPacket = packet(52, EntityEvent.PERMISSION_LEVEL_ADMINS);
        PacketRewriteResult localResult = definition.handlePlayback(
                frame(), descriptor(), codec(localPacket, encodes, decodes), context);
        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, localResult.decision());
        assertTrue(localResult.packet().isEmpty());
        assertEquals(2, decodes.get());
        assertEquals(1, encodes.get());
    }

    @Test
    void nativeProtocolUsesIdThirtyFourAndTheExactIntByteBody() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundEntityEventPacket original = packet(0x01020304, 0xFF);
        ByteBuf encoded = encodePacket(protocol, original);
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, readVarInt(wire));
            assertEquals(0x01020304, wire.readInt());
            assertEquals(0xFF, wire.readUnsignedByte());
            assertFalse(wire.isReadable());

            ClientboundEntityEventPacket decoded = assertInstanceOf(
                    ClientboundEntityEventPacket.class, decode(protocol, encoded));
            assertEquals(0x01020304, entityId(decoded));
            assertEquals(0xFF, decoded.getEventId() & 0xFF);
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedFixedWidthBody() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, new byte[0]);

        ByteBuf body = Unpooled.buffer();
        try {
            body.writeInt(123456);
            body.writeByte(EntityEvent.DEATH);
            byte[] payload = new byte[body.readableBytes()];
            body.readBytes(payload);
            for (int length = 0; length < payload.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(payload, length));
            }
        } finally {
            body.release();
        }
    }

    private static boolean isConnectionLocalStatus(int eventStatus) {
        return eventStatus >= EntityEvent.REDUCED_DEBUG_INFO
                && eventStatus <= EntityEvent.PERMISSION_LEVEL_OWNERS;
    }

    private static ClientboundEntityEventPacket packet(int entityId, int eventStatus) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(entityId);
            buffer.writeByte(eventStatus);
            return ClientboundEntityEventPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static int entityId(ClientboundEntityEventPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundEntityEventPacket.STREAM_CODEC.encode(buffer, packet);
            return buffer.readInt();
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

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(
                0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(
            ClientboundEntityEventPacket decoded,
            AtomicInteger encodes,
            AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                assertInstanceOf(ClientboundEntityEventPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
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
