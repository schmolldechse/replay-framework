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
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
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
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id131UpdateAttributesPacketDefinitionTest {

    private static final int PACKET_ID = 131;
    private static final String TYPE_NAME = "play.clientbound.minecraft:update_attributes";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000131");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000132");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersAttributesAsCheckpointRelevantEntityState() {
        var definition = new Id131UpdateAttributesPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x83, Id131UpdateAttributesPacketDefinition.PACKET_ID);
        assertEquals("minecraft:update_attributes", Id131UpdateAttributesPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id131UpdateAttributesPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id131UpdateAttributesPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeCodecUsesEntityVarIntAndOrderedRegistryBackedAttributeSnapshots() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundUpdateAttributesPacket expected = packet(0x123456, attributeValues());
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(
                    encoded.duplicate(), registryAccess());

            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(0x123456, fields.readVarInt());
            assertEquals(3, fields.readVarInt());
            for (Id131UpdateAttributesPacketDefinition.AttributeValue expectedAttribute
                    : attributeValues()) {
                assertEquals(expectedAttribute.attribute(), Attribute.STREAM_CODEC.decode(fields));
                assertEquals(expectedAttribute.baseValue(), fields.readDouble());
                assertEquals(expectedAttribute.modifiers().size(), fields.readVarInt());
                for (AttributeModifier expectedModifier : expectedAttribute.modifiers()) {
                    assertEquals(expectedModifier.id(), Identifier.STREAM_CODEC.decode(fields));
                    assertEquals(expectedModifier.amount(), fields.readDouble());
                    assertEquals(
                            expectedModifier.operation(),
                            AttributeModifier.Operation.STREAM_CODEC.decode(fields));
                }
            }
            assertFalse(fields.isReadable());

            ClientboundUpdateAttributesPacket decoded = assertInstanceOf(
                    ClientboundUpdateAttributesPacket.class, decode(protocol, encoded));
            assertEquals(expected.getEntityId(), decoded.getEntityId());
            assertSnapshotsEqual(attributeValues(), decoded.getValues());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecAcceptsEmptySnapshotsAndEnforcesThe128AttributeLimit() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundUpdateAttributesPacket empty = packet(7, List.of());
        ClientboundUpdateAttributesPacket decodedEmpty = roundTrip(protocol, empty);
        assertEquals(7, decodedEmpty.getEntityId());
        assertTrue(decodedEmpty.getValues().isEmpty());

        List<Id131UpdateAttributesPacketDefinition.AttributeValue> atLimit = new ArrayList<>();
        for (int index = 0; index < 128; index++) {
            atLimit.add(attribute(Attributes.MAX_HEALTH, index, List.of()));
        }
        ClientboundUpdateAttributesPacket decodedAtLimit = roundTrip(protocol, packet(8, atLimit));
        assertEquals(128, decodedAtLimit.getValues().size());

        List<Id131UpdateAttributesPacketDefinition.AttributeValue> aboveLimit = new ArrayList<>(atLimit);
        aboveLimit.add(attribute(Attributes.MAX_HEALTH, 128, List.of()));
        assertThrows(RuntimeException.class, () -> encodePacket(protocol, packet(9, aboveLimit)));
    }

    @Test
    void readsAndRebuildsEveryAttributeFieldWithoutCollapsingRepeatedAttributes() {
        var definition = new Id131UpdateAttributesPacketDefinition();
        ClientboundUpdateAttributesPacket original = packet(42, attributeValues());

        var value = definition.read(original);
        assertEquals(42, value.entityId());
        assertEquals(attributeValues(), value.attributes());

        ClientboundUpdateAttributesPacket rebuilt = definition.rebuild(value);
        assertEquals(42, rebuilt.getEntityId());
        assertSnapshotsEqual(attributeValues(), rebuilt.getValues());
        assertEquals(
                value.attributes().get(0).attribute(),
                value.attributes().get(2).attribute(),
                "separate snapshots for one attribute must remain separate and ordered");
    }

    @Test
    void rebuildPreservesNegativeZeroForAttributesWhoseDefaultIsPositiveZero() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundUpdateAttributesPacket decoded = packetWithNegativeZeroBase(protocol);
        var definition = new Id131UpdateAttributesPacketDefinition();

        assertEquals(
                Double.doubleToRawLongBits(-0.0D),
                Double.doubleToRawLongBits(decoded.getValues().getFirst().base()));
        ClientboundUpdateAttributesPacket rebuilt = definition.rebuild(definition.read(decoded));
        assertEquals(
                Double.doubleToRawLongBits(-0.0D),
                Double.doubleToRawLongBits(rebuilt.getValues().getFirst().base()));
    }

    @Test
    void playbackRewritesOnlyTheEntityIdAndPreservesAllAttributeSnapshots() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id131UpdateAttributesPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = codec(protocol);
        int recordedEntityId = 1701;

        PacketRewriteResult result = definition.handlePlayback(
                frame(body(protocol, packet(recordedEntityId, attributeValues()))),
                descriptor(),
                codec,
                context);
        ClientboundUpdateAttributesPacket rebuilt = assertInstanceOf(
                ClientboundUpdateAttributesPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(recordedEntityId), rebuilt.getEntityId());
        assertNotEquals(recordedEntityId, rebuilt.getEntityId());
        assertSnapshotsEqual(attributeValues(), rebuilt.getValues());

        PacketCodec.EncodedPacket encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        ClientboundUpdateAttributesPacket decoded = assertInstanceOf(
                ClientboundUpdateAttributesPacket.class,
                decodePayload(protocol, encoded.payload()));
        assertEquals(rebuilt.getEntityId(), decoded.getEntityId());
        assertSnapshotsEqual(attributeValues(), decoded.getValues());
    }

    @Test
    void detachesAttributeAndModifierListsFromMutableInputs() {
        List<AttributeModifier> modifierInput = new ArrayList<>(List.of(modifier(
                "replay:detached",
                2.5D,
                AttributeModifier.Operation.ADD_VALUE)));
        var attribute = new Id131UpdateAttributesPacketDefinition.AttributeValue(
                Attributes.MAX_HEALTH, 40.0D, modifierInput);
        List<Id131UpdateAttributesPacketDefinition.AttributeValue> attributeInput =
                new ArrayList<>(List.of(attribute));
        var value = new Id131UpdateAttributesPacketDefinition.Value(3, attributeInput);

        modifierInput.clear();
        attributeInput.clear();

        assertEquals(1, value.attributes().size());
        assertEquals(1, value.attributes().getFirst().modifiers().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> value.attributes().getFirst().modifiers().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.attributes().clear());
    }

    @Test
    void rejectsModifierIdsRepeatedWithinOneAttributeSnapshot() {
        Identifier duplicateId = Identifier.parse("replay:duplicate_modifier");

        assertThrows(IncompatibleAdapterException.class, () ->
                new Id131UpdateAttributesPacketDefinition.AttributeValue(
                        Attributes.MAX_HEALTH,
                        20.0D,
                        List.of(
                                new AttributeModifier(
                                        duplicateId,
                                        1.0D,
                                        AttributeModifier.Operation.ADD_VALUE),
                                new AttributeModifier(
                                        duplicateId,
                                        2.0D,
                                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE))));
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedAttributePacketPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(23, attributeValues()));

        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(completeBody, length));
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(protocol, packet(24, attributeValues()));
        try {
            wire.writeByte(0x7A);
            ClientboundUpdateAttributesPacket decoded = assertInstanceOf(
                    ClientboundUpdateAttributesPacket.class, decode(protocol, wire));

            assertEquals(24, decoded.getEntityId());
            assertSnapshotsEqual(attributeValues(), decoded.getValues());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void validatesDescriptorBeforeDecodingOrEncoding() {
        var definition = new Id131UpdateAttributesPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);
        var packet = packet(2, attributeValues());

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                packet, descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
        assertEquals(0, decodes.get());
    }

    private static List<Id131UpdateAttributesPacketDefinition.AttributeValue> attributeValues() {
        return List.of(
                attribute(
                        Attributes.MAX_HEALTH,
                        40.5D,
                        List.of(
                                modifier("replay:add_value", -2.25D,
                                        AttributeModifier.Operation.ADD_VALUE),
                                modifier("replay:add_base", 0.125D,
                                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
                                modifier("replay:add_total", 1.75D,
                                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))),
                attribute(Attributes.MOVEMENT_SPEED, -0.0D, List.of()),
                attribute(
                        Attributes.MAX_HEALTH,
                        Double.NaN,
                        List.of(modifier(
                                "replay:non_finite_modifier",
                                Double.POSITIVE_INFINITY,
                                AttributeModifier.Operation.ADD_VALUE))));
    }

    private static Id131UpdateAttributesPacketDefinition.AttributeValue attribute(
            Holder<Attribute> attribute,
            double baseValue,
            List<AttributeModifier> modifiers) {
        return new Id131UpdateAttributesPacketDefinition.AttributeValue(
                attribute, baseValue, modifiers);
    }

    private static AttributeModifier modifier(
            String id, double amount, AttributeModifier.Operation operation) {
        return new AttributeModifier(Identifier.parse(id), amount, operation);
    }

    private static ClientboundUpdateAttributesPacket packet(
            int entityId,
            List<Id131UpdateAttributesPacketDefinition.AttributeValue> attributes) {
        List<AttributeInstance> instances = attributes.stream()
                .map(attribute -> {
                    AttributeInstance instance = new AttributeInstance(
                            attribute.attribute(), ignored -> {});
                    instance.setBaseValue(attribute.baseValue());
                    attribute.modifiers().forEach(instance::addTransientModifier);
                    return instance;
                })
                .toList();
        return new ClientboundUpdateAttributesPacket(entityId, instances);
    }

    private static void assertSnapshotsEqual(
            List<Id131UpdateAttributesPacketDefinition.AttributeValue> expected,
            List<ClientboundUpdateAttributesPacket.AttributeSnapshot> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            Id131UpdateAttributesPacketDefinition.AttributeValue expectedValue = expected.get(index);
            ClientboundUpdateAttributesPacket.AttributeSnapshot actualValue = actual.get(index);
            assertEquals(expectedValue.attribute(), actualValue.attribute());
            assertEquals(
                    Double.doubleToRawLongBits(expectedValue.baseValue()),
                    Double.doubleToRawLongBits(actualValue.base()));
            assertEquals(expectedValue.modifiers(), List.copyOf(actualValue.modifiers()));
        }
    }

    private static ClientboundUpdateAttributesPacket roundTrip(
            ProtocolInfo<?> protocol, ClientboundUpdateAttributesPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            return assertInstanceOf(ClientboundUpdateAttributesPacket.class, decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static ClientboundUpdateAttributesPacket packetWithNegativeZeroBase(
            ProtocolInfo<?> protocol) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            VarInt.write(wire, 31);
            VarInt.write(wire, 1);
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(wire, registryAccess());
            Attribute.STREAM_CODEC.encode(fields, Attributes.ARMOR);
            fields.writeDouble(-0.0D);
            fields.writeVarInt(0);
            return assertInstanceOf(ClientboundUpdateAttributesPacket.class, decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    private static byte[] body(
            ProtocolInfo<?> protocol, ClientboundUpdateAttributesPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            return bytes(fields);
        } finally {
            encoded.release();
        }
    }

    private static Object decodePayload(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(payload);
            return decode(protocol, wire);
        } finally {
            wire.release();
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

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
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
                ClientboundUpdateAttributesPacket nativePacket = assertInstanceOf(
                        ClientboundUpdateAttributesPacket.class, packet);
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
