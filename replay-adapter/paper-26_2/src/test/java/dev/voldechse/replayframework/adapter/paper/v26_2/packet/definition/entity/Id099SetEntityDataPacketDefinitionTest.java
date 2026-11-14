package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.util.sanitizer.ItemObfuscationBinding;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id099SetEntityDataPacketDefinitionTest {

    private static final int PACKET_ID = 99;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_entity_data";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RECORDED_REFERENCE =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        installTestGlobalConfiguration();
    }

    @Test
    void classifiesMetadataAsCheckpointRelevantIdentityBearingState() {
        var definition = new Id099SetEntityDataPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x63, Id099SetEntityDataPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_entity_data", Id099SetEntityDataPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id099SetEntityDataPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id099SetEntityDataPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void nativeCodecUsesIdNinetyNineAndTheUnsignedByteTerminatorFormat() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetEntityDataPacket empty = packet(123, List.of());
        ByteBuf emptyWire = encodePacket(protocol, empty);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(emptyWire.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(123, fields.readVarInt());
            assertEquals(ClientboundSetEntityDataPacket.EOF_MARKER, fields.readUnsignedByte());
            assertFalse(fields.isReadable());
        } finally {
            emptyWire.release();
        }

        List<SynchedEntityData.DataValue<?>> allIndexes = new ArrayList<>(255);
        for (int index = 0; index <= 254; index++) {
            allIndexes.add(dataValue(index, EntityDataSerializers.BYTE, (byte) index));
        }
        assertNativeRoundTrip(protocol, packet(987_654, allIndexes));
    }

    @Test
    void nativeCodecPreservesOrderedTypedValuesAndEveryMetadataIndex() {
        ProtocolInfo<?> protocol = protocol();
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(255);
        for (int index = 0; index <= 254; index++) {
            values.add(dataValue(index, EntityDataSerializers.BYTE, (byte) (index ^ 0xA5)));
        }
        ClientboundSetEntityDataPacket original = packet(Integer.MIN_VALUE, values);
        ByteBuf encoded = encodePacket(protocol, original);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(Integer.MIN_VALUE, fields.readVarInt());
            for (int index = 0; index <= 254; index++) {
                assertEquals(index, fields.readUnsignedByte());
                assertEquals(EntityDataSerializers.getSerializedId(EntityDataSerializers.BYTE),
                        fields.readVarInt());
                assertEquals((byte) (index ^ 0xA5), fields.readByte());
            }
            assertEquals(ClientboundSetEntityDataPacket.EOF_MARKER, fields.readUnsignedByte());
            assertFalse(fields.isReadable());

            ClientboundSetEntityDataPacket decoded = assertInstanceOf(
                    ClientboundSetEntityDataPacket.class, decode(protocol, encoded));
            assertEquals(Integer.MIN_VALUE, decoded.id());
            assertEquals(255, decoded.packedItems().size());
            for (int index = 0; index <= 254; index++) {
                var item = decoded.packedItems().get(index);
                assertEquals(index, item.id());
                assertSame(EntityDataSerializers.BYTE, item.serializer());
                assertEquals((byte) (index ^ 0xA5), item.value());
            }
            assertFalse(encoded.isReadable());

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
    void nativeCodecRoundTripsRepresentativePrimitiveRegistryAndComplexValues() {
        ProtocolInfo<?> protocol = protocol();
        List<SynchedEntityData.DataValue<?>> values = List.of(
                dataValue(0, EntityDataSerializers.BYTE, (byte) 0xA5),
                dataValue(1, EntityDataSerializers.INT, Integer.MIN_VALUE),
                dataValue(2, EntityDataSerializers.LONG, Long.MAX_VALUE),
                dataValue(3, EntityDataSerializers.FLOAT, Float.intBitsToFloat(0x7FC01234)),
                dataValue(4, EntityDataSerializers.STRING, "metadata: 玩家"),
                dataValue(5, EntityDataSerializers.COMPONENT, Component.literal("name")),
                dataValue(6, EntityDataSerializers.OPTIONAL_COMPONENT,
                        Optional.of(Component.literal("optional"))),
                dataValue(7, EntityDataSerializers.ITEM_STACK, ItemStack.EMPTY),
                dataValue(8, EntityDataSerializers.BOOLEAN, true),
                dataValue(9, EntityDataSerializers.BLOCK_POS, new BlockPos(-12_345, -400, 23_456)),
                dataValue(10, EntityDataSerializers.DIRECTION, net.minecraft.core.Direction.WEST),
                dataValue(11, EntityDataSerializers.BLOCK_STATE, Blocks.STONE.defaultBlockState()),
                dataValue(12, EntityDataSerializers.POSE, net.minecraft.world.entity.Pose.SLEEPING),
                dataValue(13, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE,
                        Optional.of(EntityReference.<LivingEntity>of(RECORDED_REFERENCE))),
                dataValue(14, EntityDataSerializers.OPTIONAL_UNSIGNED_INT, OptionalInt.of(42)),
                dataValue(15, EntityDataSerializers.PARTICLES, List.of(ParticleTypes.FLAME)));

        assertNativeRoundTrip(protocol, packet(43, values));
    }

    @Test
    void rewriteMapsTargetAndLivingEntityUuidsButPreservesOtherTypedValues() {
        var definition = new Id099SetEntityDataPacketDefinition();
        var context = rewriter();
        context.trackEntityType(41, EntityTypes.FOX);
        List<SynchedEntityData.DataValue<?>> values = List.of(
                dataValue(0, EntityDataSerializers.BYTE, (byte) 0x42),
                dataValue(1, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE,
                        Optional.of(EntityReference.<LivingEntity>of(RECORDED_REFERENCE))),
                dataValue(2, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE, Optional.empty()),
                dataValue(3, EntityDataSerializers.COMPONENT, Component.literal("keep me")),
                dataValue(4, EntityDataSerializers.ITEM_STACK, ItemStack.EMPTY));
        var original = new Id099SetEntityDataPacketDefinition.Value(41, values);

        var rewritten = definition.rewrite(original, context).value().orElseThrow();

        assertEquals(context.mapEntityId(41), rewritten.entityId());
        assertNotEquals(41, rewritten.entityId());
        assertEquals(5, rewritten.packedItems().size());
        assertEquals((byte) 0x42, rewritten.packedItems().get(0).value());
        @SuppressWarnings("unchecked")
        Optional<EntityReference<LivingEntity>> mapped =
                (Optional<EntityReference<LivingEntity>>) rewritten.packedItems().get(1).value();
        assertEquals(Optional.of(context.mapUuid(RECORDED_REFERENCE)),
                mapped.map(EntityReference::getUUID));
        assertEquals(Optional.empty(), rewritten.packedItems().get(2).value());
        assertSame(values.get(0), rewritten.packedItems().get(0));
        assertSame(values.get(2), rewritten.packedItems().get(2));
        assertSame(values.get(3), rewritten.packedItems().get(3));
        assertSame(values.get(4), rewritten.packedItems().get(4));
    }

    @Test
    void rewriteMapsFireworkAndFrogEntityReferencesButKeepsPlayerParrotVariants() {
        var definition = new Id099SetEntityDataPacketDefinition();
        var context = rewriter();
        int referencedEntityId = 305;
        int targetEntityId = 100;

        for (EntityType<?> entityType : List.of(EntityTypes.FIREWORK_ROCKET, EntityTypes.FROG)) {
            context.trackEntityType(targetEntityId, entityType);
            var value = new Id099SetEntityDataPacketDefinition.Value(
                    targetEntityId,
                    List.of(dataValue(5, EntityDataSerializers.OPTIONAL_UNSIGNED_INT,
                            OptionalInt.of(referencedEntityId))));

            var rewritten = definition.rewrite(value, context).value().orElseThrow();
            OptionalInt mappedReference = (OptionalInt) rewritten.packedItems().getFirst().value();
            assertEquals(context.mapEntityId(referencedEntityId), mappedReference.getAsInt());
            assertNotEquals(referencedEntityId, mappedReference.getAsInt());
            targetEntityId++;
        }

        int playerEntityId = 801;
        context.trackEntityType(playerEntityId, EntityTypes.PLAYER);
        var playerMetadata = new Id099SetEntityDataPacketDefinition.Value(
                playerEntityId,
                List.of(dataValue(17, EntityDataSerializers.OPTIONAL_UNSIGNED_INT,
                        OptionalInt.of(3))));
        var rewrittenPlayer = definition.rewrite(playerMetadata, context).value().orElseThrow();
        assertEquals(OptionalInt.of(3), rewrittenPlayer.packedItems().getFirst().value());
    }

    @Test
    void rewriteMapsEntityPositionSourcesInsideSingleAndListParticleMetadata() {
        var definition = new Id099SetEntityDataPacketDefinition();
        var context = rewriter();
        EntityPositionSource source = entityPositionSource(712, -1.375F);
        VibrationParticleOption vibration = new VibrationParticleOption(source, 28);
        VibrationParticleOption blockVibration = new VibrationParticleOption(
                new BlockPositionSource(new BlockPos(-3, 87, 14)), 31);
        List<ParticleOptions> particles = List.of(
                vibration, ParticleTypes.FLAME, blockVibration);
        var value = new Id099SetEntityDataPacketDefinition.Value(
                55,
                List.of(
                        dataValue(16, EntityDataSerializers.PARTICLE, vibration),
                        dataValue(17, EntityDataSerializers.PARTICLES, particles),
                        dataValue(18, EntityDataSerializers.PARTICLE, blockVibration)));

        var rewritten = definition.rewrite(value, context).value().orElseThrow();

        assertEquals(context.mapEntityId(712),
                vibrationSourceId((VibrationParticleOption) rewritten.packedItems().get(0).value()));
        assertFloatBits(-1.375F,
                vibrationSourceYOffset((VibrationParticleOption) rewritten.packedItems().get(0).value()));
        assertEquals(28,
                ((VibrationParticleOption) rewritten.packedItems().get(0).value()).getArrivalInTicks());
        assertSame(blockVibration, rewritten.packedItems().get(2).value());
        @SuppressWarnings("unchecked")
        List<ParticleOptions> rewrittenParticles =
                (List<ParticleOptions>) rewritten.packedItems().get(1).value();
        assertEquals(3, rewrittenParticles.size());
        assertEquals(context.mapEntityId(712),
                vibrationSourceId((VibrationParticleOption) rewrittenParticles.getFirst()));
        assertSame(ParticleTypes.FLAME, rewrittenParticles.get(1));
        assertSame(blockVibration, rewrittenParticles.getLast());
        assertEquals(712, vibrationSourceId(vibration));
    }

    @Test
    void rejectsUnclassifiableOptionalEntityIdsAndInvalidMetadataIndices() {
        var definition = new Id099SetEntityDataPacketDefinition();
        var context = rewriter();
        var unknownType = new Id099SetEntityDataPacketDefinition.Value(
                61,
                List.of(dataValue(5, EntityDataSerializers.OPTIONAL_UNSIGNED_INT,
                        OptionalInt.of(15))));

        assertThrows(IncompatibleAdapterException.class,
                () -> definition.rewrite(unknownType, context));
        context.trackEntityType(61, EntityTypes.ARMOR_STAND);
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.rewrite(unknownType, context));
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id099SetEntityDataPacketDefinition.Value(
                        1, List.of(dataValue(255, EntityDataSerializers.BYTE, (byte) 1))));
        assertThrows(IncompatibleAdapterException.class,
                () -> new Id099SetEntityDataPacketDefinition.Value(
                        1, List.of(dataValue(-1, EntityDataSerializers.BYTE, (byte) 1))));
        assertThrows(NullPointerException.class,
                () -> new Id099SetEntityDataPacketDefinition.Value(1, null));
        assertThrows(NullPointerException.class,
                () -> new Id099SetEntityDataPacketDefinition.Value(1, java.util.Arrays.asList((
                        SynchedEntityData.DataValue<?>) null)));
    }

    @Test
    void nativeCodecRejectsUnknownSerializersAndEveryTruncatedBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        byte[] body = body(protocol, packet(73,
                List.of(dataValue(4, EntityDataSerializers.INT, Integer.MAX_VALUE))));
        for (int length = 0; length < body.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(body, length));
        }

        ByteBuf unknownSerializer = Unpooled.buffer();
        try {
            VarInt.write(unknownSerializer, PACKET_ID);
            VarInt.write(unknownSerializer, 73);
            unknownSerializer.writeByte(0);
            VarInt.write(unknownSerializer, 127);
            unknownSerializer.writeByte(ClientboundSetEntityDataPacket.EOF_MARKER);
            assertThrows(RuntimeException.class, () -> decode(protocol, unknownSerializer));
        } finally {
            unknownSerializer.release();
        }

        ByteBuf missingEndMarker = Unpooled.buffer();
        try {
            VarInt.write(missingEndMarker, PACKET_ID);
            VarInt.write(missingEndMarker, 73);
            missingEndMarker.writeByte(0);
            VarInt.write(missingEndMarker,
                    EntityDataSerializers.getSerializedId(EntityDataSerializers.BYTE));
            missingEndMarker.writeByte(7);
            assertThrows(RuntimeException.class, () -> decode(protocol, missingEndMarker));
        } finally {
            missingEndMarker.release();
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(protocol, packet(22,
                List.of(dataValue(0, EntityDataSerializers.BYTE, (byte) 9))));
        try {
            wire.writeByte(0x7A);
            ClientboundSetEntityDataPacket decoded = assertInstanceOf(
                    ClientboundSetEntityDataPacket.class, decode(protocol, wire));
            assertEquals(22, decoded.id());
            assertEquals((byte) 9, decoded.packedItems().getFirst().value());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void playbackRewritesNestedIdentityFieldsAndEncodesTheRebuiltMetadata() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id099SetEntityDataPacketDefinition();
        var context = rewriter();
        int recordedEntityId = 911;
        int referencedEntityId = 912;
        context.trackEntityType(recordedEntityId, EntityTypes.FIREWORK_ROCKET);
        ClientboundSetEntityDataPacket original = packet(recordedEntityId, List.of(
                dataValue(0, EntityDataSerializers.OPTIONAL_UNSIGNED_INT,
                        OptionalInt.of(referencedEntityId)),
                dataValue(1, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE,
                        Optional.of(EntityReference.<LivingEntity>of(RECORDED_REFERENCE))),
                dataValue(2, EntityDataSerializers.PARTICLE,
                        new VibrationParticleOption(entityPositionSource(referencedEntityId, 0.5F), 8))));
        byte[] payload = nativePayload(protocol, original);

        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec(protocol), context);
        ClientboundSetEntityDataPacket rebuilt = assertInstanceOf(
                ClientboundSetEntityDataPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(recordedEntityId), rebuilt.id());
        assertEquals(context.mapEntityId(referencedEntityId),
                ((OptionalInt) rebuilt.packedItems().get(0).value()).getAsInt());
        @SuppressWarnings("unchecked")
        Optional<EntityReference<LivingEntity>> reference =
                (Optional<EntityReference<LivingEntity>>) rebuilt.packedItems().get(1).value();
        assertEquals(context.mapUuid(RECORDED_REFERENCE), reference.orElseThrow().getUUID());
        assertEquals(context.mapEntityId(referencedEntityId),
                vibrationSourceId((VibrationParticleOption) rebuilt.packedItems().get(2).value()));

        PacketCodec.EncodedPacket encoded = definition.encode(rebuilt, descriptor(), codec(protocol));
        assertEquals(PACKET_ID, encoded.packetId());
        ClientboundSetEntityDataPacket decoded = assertInstanceOf(
                ClientboundSetEntityDataPacket.class,
                decodePayload(protocol, encoded.payload()));
        assertEquals(rebuilt.id(), decoded.id());
        assertEquals(rebuilt.packedItems().size(), decoded.packedItems().size());
    }

    @Test
    void validatesDescriptorBeforeDecodingOrEncoding() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id099SetEntityDataPacketDefinition();
        PacketCodec<Object> codec = codec(protocol);
        var original = packet(2, List.of());

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                original, descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetEntityDataPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            ClientboundSetEntityDataPacket decoded = assertInstanceOf(
                    ClientboundSetEntityDataPacket.class, decode(protocol, encoded));
            assertEquals(expected.id(), decoded.id());
            assertEquals(expected.packedItems().size(), decoded.packedItems().size());
            for (int index = 0; index < expected.packedItems().size(); index++) {
                var expectedItem = expected.packedItems().get(index);
                var actualItem = decoded.packedItems().get(index);
                assertEquals(expectedItem.id(), actualItem.id());
                assertSame(expectedItem.serializer(), actualItem.serializer());
                assertEquals(expectedItem.value(), actualItem.value());
            }
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

    private static <T> SynchedEntityData.DataValue<T> dataValue(
            int id, EntityDataSerializer<T> serializer, T value) {
        return new SynchedEntityData.DataValue<>(id, serializer, value);
    }

    private static ClientboundSetEntityDataPacket packet(
            int entityId, List<SynchedEntityData.DataValue<?>> values) {
        return new ClientboundSetEntityDataPacket(entityId, values);
    }

    private static EntityPositionSource entityPositionSource(int entityId, float yOffset) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            VarInt.write(encoded, entityId);
            encoded.writeFloat(yOffset);
            return EntityPositionSource.STREAM_CODEC.decode(encoded);
        } finally {
            encoded.release();
        }
    }

    private static int vibrationSourceId(VibrationParticleOption vibration) {
        EntityPositionSource source = assertInstanceOf(
                EntityPositionSource.class, vibration.getDestination());
        ByteBuf encoded = Unpooled.buffer();
        try {
            EntityPositionSource.STREAM_CODEC.encode(encoded, source);
            return VarInt.read(encoded);
        } finally {
            encoded.release();
        }
    }

    private static float vibrationSourceYOffset(VibrationParticleOption vibration) {
        EntityPositionSource source = assertInstanceOf(
                EntityPositionSource.class, vibration.getDestination());
        ByteBuf encoded = Unpooled.buffer();
        try {
            EntityPositionSource.STREAM_CODEC.encode(encoded, source);
            VarInt.read(encoded);
            return encoded.readFloat();
        } finally {
            encoded.release();
        }
    }

    private static void assertFloatBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static byte[] body(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ByteBuf copy = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(copy));
            return bytes(copy);
        } finally {
            encoded.release();
        }
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundSetEntityDataPacket packet) {
        return body(protocol, packet);
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
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static void installTestGlobalConfiguration() {
        try {
            GlobalConfiguration configuration = GlobalConfiguration.get();
            if (configuration == null) {
                configuration = new GlobalConfiguration();
                configuration.misc = configuration.new Misc();
                configuration.packetLimiter = configuration.new PacketLimiter();
                configuration.packetLimiter.allPackets =
                        new GlobalConfiguration.PacketLimiter.PacketLimit(
                                0.0D,
                                0.0D,
                                GlobalConfiguration.PacketLimiter.PacketLimit.ViolateAction.DROP);
                configuration.packetLimiter.overrides = Map.of();
                Method setter = GlobalConfiguration.class.getDeclaredMethod(
                        "set", GlobalConfiguration.class);
                setter.setAccessible(true);
                setter.invoke(null, configuration);
            }
            if (configuration.anticheat == null) {
                configuration.anticheat = configuration.new Anticheat();
            }
            if (configuration.anticheat.obfuscation == null) {
                configuration.anticheat.obfuscation =
                        configuration.anticheat.new Obfuscation();
            }
            if (configuration.anticheat.obfuscation.items == null) {
                // The packet encoder only reads binding.level. A full Paper item binding needs a
                // running server and bound item-component registries, which this codec test omits.
                configuration.anticheat.obfuscation.items = testItemObfuscationItems();
            }
            if (configuration.anticheat.obfuscation.items.binding == null) {
                configuration.anticheat.obfuscation.items.bindDataSanitizer();
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not initialize Paper test configuration", failure);
        }
    }

    private static GlobalConfiguration.Anticheat.Obfuscation.Items testItemObfuscationItems() {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            var unsafeField = unsafeType.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            var allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
            var items = (GlobalConfiguration.Anticheat.Obfuscation.Items) allocateInstance.invoke(
                    unsafe, GlobalConfiguration.Anticheat.Obfuscation.Items.class);
            ItemObfuscationBinding binding = (ItemObfuscationBinding) allocateInstance.invoke(
                    unsafe, ItemObfuscationBinding.class);
            Class<?> levelType = Class.forName(
                    "io.papermc.paper.util.sanitizer.ItemObfuscationSession$ObfuscationLevel");
            Object noOpLevel = null;
            for (Object candidate : levelType.getEnumConstants()) {
                if (((Enum<?>) candidate).name().equals("NONE")) {
                    noOpLevel = candidate;
                    break;
                }
            }
            if (noOpLevel == null) {
                throw new AssertionError("Paper's no-op obfuscation level is unavailable");
            }
            var levelField = ItemObfuscationBinding.class.getDeclaredField("level");
            long levelOffset = (long) unsafeType
                    .getMethod("objectFieldOffset", java.lang.reflect.Field.class)
                    .invoke(unsafe, levelField);
            unsafeType
                    .getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, binding, levelOffset, noOpLevel);
            items.binding = binding;
            return items;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not create Paper's no-op item obfuscation binding", failure);
        }
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
                ClientboundSetEntityDataPacket nativePacket = assertInstanceOf(
                        ClientboundSetEntityDataPacket.class, packet);
                ByteBuf wire = encodePacket(protocol, nativePacket);
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
                return decodePayload(protocol, frame.payload());
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
