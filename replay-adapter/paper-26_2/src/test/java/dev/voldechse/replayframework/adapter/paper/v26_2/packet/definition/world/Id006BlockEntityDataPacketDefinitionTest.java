package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id006BlockEntityDataPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:block_entity_data";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesBlockEntityDataAsSafeCheckpointState() {
        var definition = new Id006BlockEntityDataPacketDefinition();

        assertEquals(6, definition.packetId());
        assertEquals(0x06, Id006BlockEntityDataPacketDefinition.PACKET_ID);
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
    void preservesOpaqueNestedNbtWithoutIdentityRewriting() {
        var definition = new Id006BlockEntityDataPacketDefinition();
        BlockPos position = new BlockPos(-31, 72, 44);
        CompoundTag source = complexTag();
        CompoundTag expected = source.copy();
        var value = definition.read(new ClientboundBlockEntityDataPacket(
                position, BlockEntityTypes.CHEST, source));

        source.putString("source_mutation", "must not leak");
        CompoundTag exposedCopy = value.tag();
        exposedCopy.putString("accessor_mutation", "must not leak");
        var rewritten = definition.rewrite(value, rewriter()).value().orElseThrow();
        ClientboundBlockEntityDataPacket rebuilt = definition.rebuild(rewritten);

        assertSame(value, rewritten);
        assertEquals(position, rebuilt.getPos());
        assertSame(BlockEntityTypes.CHEST, rebuilt.getType());
        assertEquals(expected, rebuilt.getTag());
        assertNotSame(source, rebuilt.getTag());
        assertNotSame(exposedCopy, rebuilt.getTag());
    }

    @Test
    void acceptsEmptyTagsAndRejectsInvalidSemanticValues() {
        var definition = new Id006BlockEntityDataPacketDefinition();
        CompoundTag empty = new CompoundTag();
        var value = definition.read(new ClientboundBlockEntityDataPacket(
                BlockPos.ZERO, BlockEntityTypes.BEACON, empty));

        assertTrue(value.tag().isEmpty());
        assertThrows(NullPointerException.class, () ->
                new Id006BlockEntityDataPacketDefinition.Value(
                        null, BlockEntityTypes.CHEST, empty));
        assertThrows(NullPointerException.class, () ->
                new Id006BlockEntityDataPacketDefinition.Value(
                        BlockPos.ZERO, null, empty));
        assertThrows(NullPointerException.class, () ->
                new Id006BlockEntityDataPacketDefinition.Value(
                        BlockPos.ZERO, BlockEntityTypes.CHEST, null));
        assertThrows(IncompatibleAdapterException.class, () ->
                definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id006BlockEntityDataPacketDefinition();
        CompoundTag tag = complexTag();
        ClientboundBlockEntityDataPacket original = new ClientboundBlockEntityDataPacket(
                new BlockPos(10, -64, -20), BlockEntityTypes.SIGN, tag);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), rewriter());
        ClientboundBlockEntityDataPacket rebuilt = assertInstanceOf(
                ClientboundBlockEntityDataPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(original.getPos(), rebuilt.getPos());
        assertSame(original.getType(), rebuilt.getType());
        assertEquals(tag, rebuilt.getTag());
        assertNotSame(tag, rebuilt.getTag());
        assertEquals(6, definition.encode(rebuilt, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolCoversEveryRegisteredTypePositionBoundaryAndNbtFamily() {
        ProtocolInfo<?> protocol = protocol();
        CompoundTag empty = new CompoundTag();
        BlockPos origin = new BlockPos(0, 64, 0);

        BuiltInRegistries.BLOCK_ENTITY_TYPE.stream().forEach(type ->
                assertNativeRoundTrip(protocol, origin, type, empty));
        for (BlockPos position : new BlockPos[] {
            new BlockPos(-33_554_432, -2_048, -33_554_432),
            BlockPos.ZERO,
            new BlockPos(33_554_431, 2_047, 33_554_431)
        }) {
            assertNativeRoundTrip(protocol, position, BlockEntityTypes.CHEST, complexTag());
        }
    }

    @Test
    void nativeProtocolRejectsIncompleteOrInvalidBodies() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeDecodeFails(protocol, buffer -> {});
        assertNativeDecodeFails(protocol, buffer -> buffer.writeInt(0));
        assertNativeDecodeFails(protocol, buffer -> buffer.writeLong(0));
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            for (int index = 0; index < 5; index++) {
                buffer.writeByte(0x80);
            }
        });
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            writeVarInt(buffer, Integer.MAX_VALUE);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            writeVarInt(buffer, 0);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            buffer.writeLong(0);
            writeVarInt(buffer, 0);
            buffer.writeByte(1);
        });
    }

    private static CompoundTag complexTag() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("byte", Byte.MIN_VALUE);
        tag.putShort("short", Short.MIN_VALUE);
        tag.putInt("int", Integer.MIN_VALUE);
        tag.putLong("long", Long.MIN_VALUE);
        tag.putFloat("float", -123.5F);
        tag.putDouble("double", Double.MAX_VALUE);
        tag.putString("string", "Replay \u2603 data");
        tag.putByteArray("bytes", new byte[] {Byte.MIN_VALUE, 0, Byte.MAX_VALUE});
        tag.putIntArray("ints", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
        tag.putLongArray("longs", new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE});
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("first"));
        list.add(StringTag.valueOf("second"));
        tag.put("list", list);
        CompoundTag nested = new CompoundTag();
        nested.putBoolean("boolean", true);
        nested.putString("profile_uuid_shape", SESSION_ID.toString());
        tag.put("nested", nested);
        return tag;
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            BlockPos expectedPosition,
            BlockEntityType<?> expectedType,
            CompoundTag expectedTag) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, new ClientboundBlockEntityDataPacket(
                    expectedPosition, expectedType, expectedTag));

            ByteBuf wireView = encoded.duplicate();
            assertEquals(6, readVarInt(wireView));
            assertEquals(expectedPosition.asLong(), wireView.readLong());
            assertEquals(BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(expectedType), readVarInt(wireView));
            assertEquals(expectedTag, ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(wireView));
            assertFalse(wireView.isReadable());

            ClientboundBlockEntityDataPacket decoded = assertInstanceOf(
                    ClientboundBlockEntityDataPacket.class, decode(protocol, encoded));
            assertEquals(expectedPosition, decoded.getPos());
            assertSame(expectedType, decoded.getType());
            assertEquals(expectedTag, decoded.getTag());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            Consumer<ByteBuf> bodyWriter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            writeVarInt(encoded, 6);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
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
                6,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 6, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundBlockEntityDataPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundBlockEntityDataPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        6,
                        new byte[] {6});
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
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
