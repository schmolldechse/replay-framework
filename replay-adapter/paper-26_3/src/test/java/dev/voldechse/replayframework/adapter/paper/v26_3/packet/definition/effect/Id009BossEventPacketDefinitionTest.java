package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect;

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
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.BossEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id009BossEventPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:boss_event";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RECORDED_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SECOND_RECORDED_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesBossEventsAsIdentityBearingCheckpointState() {
        var definition = new Id009BossEventPacketDefinition();

        assertEquals(9, definition.packetId());
        assertEquals(0x09, Id009BossEventPacketDefinition.PACKET_ID);
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
    void readsRewritesAndRebuildsAllSixOperations() {
        var definition = new Id009BossEventPacketDefinition();
        TestBossEvent event = event(RECORDED_ID);
        List<ClientboundBossEventPacket> packets = List.of(
                ClientboundBossEventPacket.createAddPacket(event),
                ClientboundBossEventPacket.createRemovePacket(RECORDED_ID),
                ClientboundBossEventPacket.createUpdateProgressPacket(event),
                ClientboundBossEventPacket.createUpdateNamePacket(event),
                ClientboundBossEventPacket.createUpdateStylePacket(event),
                ClientboundBossEventPacket.createUpdatePropertiesPacket(event));
        List<Id009BossEventPacketDefinition.Operation> operations = List.of(
                new Id009BossEventPacketDefinition.Add(
                        event.getName(), event.getProgress(), event.getColor(), event.getOverlay(),
                        event.shouldDarkenScreen(), event.shouldPlayBossMusic(), event.shouldCreateWorldFog()),
                new Id009BossEventPacketDefinition.Remove(),
                new Id009BossEventPacketDefinition.UpdateProgress(event.getProgress()),
                new Id009BossEventPacketDefinition.UpdateName(event.getName()),
                new Id009BossEventPacketDefinition.UpdateStyle(event.getColor(), event.getOverlay()),
                new Id009BossEventPacketDefinition.UpdateProperties(
                        event.shouldDarkenScreen(), event.shouldPlayBossMusic(), event.shouldCreateWorldFog()));
        Paper26_3ReplayPacketRewriter rewriter = rewriter();
        UUID mappedId = rewriter.mapUuid(RECORDED_ID);

        for (int index = 0; index < packets.size(); index++) {
            Id009BossEventPacketDefinition.Value value = definition.read(packets.get(index));
            assertEquals(RECORDED_ID, value.id());
            assertEquals(operations.get(index), value.operation());

            Id009BossEventPacketDefinition.Value rewritten = definition
                    .rewrite(value, rewriter)
                    .value()
                    .orElseThrow();
            assertEquals(mappedId, rewritten.id());
            assertSame(value.operation(), rewritten.operation());

            assertEquals(rewritten, definition.read(definition.rebuild(rewritten)));
        }
    }

    @Test
    void keepsBossBarMappingsStableDistinctAndOutsideReservedIds() {
        var definition = new Id009BossEventPacketDefinition();
        Paper26_3ReplayPacketRewriter rewriter = rewriter();
        Id009BossEventPacketDefinition.Operation operation =
                new Id009BossEventPacketDefinition.Remove();

        UUID first = definition.rewrite(
                        new Id009BossEventPacketDefinition.Value(RECORDED_ID, operation), rewriter)
                .value().orElseThrow().id();
        UUID repeated = definition.rewrite(
                        new Id009BossEventPacketDefinition.Value(RECORDED_ID, operation), rewriter)
                .value().orElseThrow().id();
        UUID second = definition.rewrite(
                        new Id009BossEventPacketDefinition.Value(SECOND_RECORDED_ID, operation), rewriter)
                .value().orElseThrow().id();

        assertEquals(first, repeated);
        assertNotEquals(first, second);
        assertNotEquals(RECORDED_ID, first);
        assertNotEquals(OBSERVER_ID, first);
    }

    @Test
    void supportsEveryColorAndOverlayInAddAndStyleOperations() {
        var definition = new Id009BossEventPacketDefinition();

        for (BossEvent.BossBarColor color : BossEvent.BossBarColor.values()) {
            for (BossEvent.BossBarOverlay overlay : BossEvent.BossBarOverlay.values()) {
                TestBossEvent event = event(RECORDED_ID);
                event.setColor(color);
                event.setOverlay(overlay);

                var add = assertInstanceOf(
                        Id009BossEventPacketDefinition.Add.class,
                        definition.read(ClientboundBossEventPacket.createAddPacket(event)).operation());
                var style = assertInstanceOf(
                        Id009BossEventPacketDefinition.UpdateStyle.class,
                        definition.read(ClientboundBossEventPacket.createUpdateStylePacket(event)).operation());

                assertEquals(color, add.color());
                assertEquals(overlay, add.overlay());
                assertEquals(color, style.color());
                assertEquals(overlay, style.overlay());
            }
        }
    }

    @Test
    void supportsEveryDefinedPropertiesCombination() {
        var definition = new Id009BossEventPacketDefinition();

        for (int flags = 0; flags <= 0x07; flags++) {
            TestBossEvent event = event(RECORDED_ID);
            event.setDarkenScreen((flags & 0x01) != 0);
            event.setPlayBossMusic((flags & 0x02) != 0);
            event.setCreateWorldFog((flags & 0x04) != 0);

            var add = assertInstanceOf(
                    Id009BossEventPacketDefinition.Add.class,
                    definition.read(ClientboundBossEventPacket.createAddPacket(event)).operation());
            var properties = assertInstanceOf(
                    Id009BossEventPacketDefinition.UpdateProperties.class,
                    definition.read(ClientboundBossEventPacket.createUpdatePropertiesPacket(event)).operation());

            assertFlags(flags, add.darkenScreen(), add.playMusic(), add.createWorldFog());
            assertFlags(flags, properties.darkenScreen(), properties.playMusic(), properties.createWorldFog());
        }
    }

    @Test
    void preservesEveryRelevantFloatClassWithoutClamping() {
        var definition = new Id009BossEventPacketDefinition();
        int[] progressBits = {
            Float.floatToRawIntBits(-0.0F),
            Float.floatToRawIntBits(0.0F),
            Float.floatToRawIntBits(1.0F),
            Float.floatToRawIntBits(-2.5F),
            Float.floatToRawIntBits(1.5F),
            Float.floatToRawIntBits(Float.MIN_VALUE),
            Float.floatToRawIntBits(Float.MAX_VALUE),
            Float.floatToRawIntBits(Float.NEGATIVE_INFINITY),
            Float.floatToRawIntBits(Float.POSITIVE_INFINITY),
            0x7fc01234
        };

        for (int bits : progressBits) {
            float progress = Float.intBitsToFloat(bits);
            TestBossEvent event = event(RECORDED_ID);
            event.setProgress(progress);
            var value = definition.read(
                    ClientboundBossEventPacket.createUpdateProgressPacket(event));
            var update = assertInstanceOf(
                    Id009BossEventPacketDefinition.UpdateProgress.class, value.operation());
            assertEquals(bits, Float.floatToRawIntBits(update.progress()));

            var rebuilt = assertInstanceOf(
                    Id009BossEventPacketDefinition.UpdateProgress.class,
                    definition.read(definition.rebuild(value)).operation());
            assertEquals(bits, Float.floatToRawIntBits(rebuilt.progress()));
        }
    }

    @Test
    void validatesValueVariantsAndDecodedType() {
        var definition = new Id009BossEventPacketDefinition();

        assertThrows(NullPointerException.class, () ->
                new Id009BossEventPacketDefinition.Value(
                        null, new Id009BossEventPacketDefinition.Remove()));
        assertThrows(NullPointerException.class, () ->
                new Id009BossEventPacketDefinition.Value(RECORDED_ID, null));
        assertThrows(NullPointerException.class, () ->
                new Id009BossEventPacketDefinition.Add(
                        null, 1.0F, BossEvent.BossBarColor.PINK,
                        BossEvent.BossBarOverlay.PROGRESS, false, false, false));
        assertThrows(NullPointerException.class, () ->
                new Id009BossEventPacketDefinition.UpdateName(null));
        assertThrows(NullPointerException.class, () ->
                new Id009BossEventPacketDefinition.UpdateStyle(
                        null, BossEvent.BossBarOverlay.PROGRESS));
        assertThrows(NullPointerException.class, () ->
                new Id009BossEventPacketDefinition.UpdateStyle(
                        BossEvent.BossBarColor.PINK, null));
        assertThrows(IncompatibleAdapterException.class, () ->
                definition.rewrite(new Object(), descriptor(), rewriter()));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id009BossEventPacketDefinition();
        ClientboundBossEventPacket original =
                ClientboundBossEventPacket.createAddPacket(event(RECORDED_ID));
        Paper26_3ReplayPacketRewriter rewriter = rewriter();

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), rewriter);
        ClientboundBossEventPacket rebuilt = assertInstanceOf(
                ClientboundBossEventPacket.class, result.packet().orElseThrow());
        Id009BossEventPacketDefinition.Value value = definition.read(rebuilt);

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(rewriter.mapUuid(RECORDED_ID), value.id());
        assertInstanceOf(Id009BossEventPacketDefinition.Add.class, value.operation());
        assertEquals(9, definition.encode(rebuilt, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeCodecUsesExactIdsAndVariantFieldOrder() {
        TestBossEvent event = event(RECORDED_ID);
        List<ClientboundBossEventPacket> packets = List.of(
                ClientboundBossEventPacket.createAddPacket(event),
                ClientboundBossEventPacket.createRemovePacket(RECORDED_ID),
                ClientboundBossEventPacket.createUpdateProgressPacket(event),
                ClientboundBossEventPacket.createUpdateNamePacket(event),
                ClientboundBossEventPacket.createUpdateStylePacket(event),
                ClientboundBossEventPacket.createUpdatePropertiesPacket(event));
        ProtocolInfo<?> protocol = protocol();

        for (int action = 0; action < packets.size(); action++) {
            ClientboundBossEventPacket packet = packets.get(action);
            ByteBuf encoded = Unpooled.buffer();
            try {
                encode(protocol, encoded, packet);
                ByteBuf packetIdView = encoded.duplicate();
                assertEquals(9, readVarInt(packetIdView));

                ClientboundBossEventPacket decoded = assertInstanceOf(
                        ClientboundBossEventPacket.class, decode(protocol, encoded));
                assertEquivalent(
                        new Id009BossEventPacketDefinition().read(packet),
                        new Id009BossEventPacketDefinition().read(decoded));
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
            }

            assertWireBody(packet, action, event);
        }
    }

    @Test
    void nativeCodecCanonicalizesReservedPropertyBits() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = Unpooled.buffer();
        ByteBuf rebuilt = Unpooled.buffer();
        try {
            writeVarInt(encoded, 9);
            writeUuid(encoded, RECORDED_ID);
            writeVarInt(encoded, 5);
            encoded.writeByte(0xFF);

            ClientboundBossEventPacket decoded = assertInstanceOf(
                    ClientboundBossEventPacket.class, decode(protocol, encoded));
            var properties = assertInstanceOf(
                    Id009BossEventPacketDefinition.UpdateProperties.class,
                    new Id009BossEventPacketDefinition().read(decoded).operation());
            assertTrue(properties.darkenScreen());
            assertTrue(properties.playMusic());
            assertTrue(properties.createWorldFog());

            encode(protocol, rebuilt, decoded);
            assertEquals(9, readVarInt(rebuilt));
            assertEquals(RECORDED_ID, readUuid(rebuilt));
            assertEquals(5, readVarInt(rebuilt));
            assertEquals(0x07, rebuilt.readUnsignedByte());
            assertFalse(rebuilt.isReadable());
        } finally {
            encoded.release();
            rebuilt.release();
        }
    }

    @Test
    void nativeCodecRejectsUnknownActionsStylesAndIncompleteBodies() {
        ProtocolInfo<?> protocol = protocol();

        for (int action : new int[] {-1, 6, Integer.MAX_VALUE}) {
            assertNativeDecodeFails(protocol, buffer -> writeVarInt(buffer, action));
        }
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 4);
            writeVarInt(buffer, BossEvent.BossBarColor.values().length);
            writeVarInt(buffer, 0);
        });
        assertNativeDecodeFails(protocol, buffer -> {
            writeVarInt(buffer, 4);
            writeVarInt(buffer, 0);
            writeVarInt(buffer, BossEvent.BossBarOverlay.values().length);
        });
        for (int action : new int[] {0, 2, 3, 4, 5}) {
            assertNativeDecodeFails(protocol, buffer -> writeVarInt(buffer, action));
        }
        assertNativeDecodeFails(protocol, buffer -> {
            for (int index = 0; index < 5; index++) {
                buffer.writeByte(0x80);
            }
        });
    }

    private static void assertWireBody(
            ClientboundBossEventPacket packet,
            int expectedAction,
            TestBossEvent event) {
        ByteBuf source = Unpooled.buffer();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(source, registryAccess());
        try {
            ClientboundBossEventPacket.STREAM_CODEC.encode(buffer, packet);
            assertEquals(RECORDED_ID, buffer.readUUID());
            assertEquals(expectedAction, buffer.readVarInt());
            switch (expectedAction) {
                case 0 -> {
                    assertEquals(event.getName().getString(),
                            net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC
                                    .decode(buffer)
                                    .getString());
                    assertEquals(
                            Float.floatToRawIntBits(event.getProgress()),
                            Float.floatToRawIntBits(buffer.readFloat()));
                    assertEquals(event.getColor().ordinal(), buffer.readVarInt());
                    assertEquals(event.getOverlay().ordinal(), buffer.readVarInt());
                    assertEquals(0x07, buffer.readUnsignedByte());
                }
                case 1 -> {
                }
                case 2 -> assertEquals(
                        Float.floatToRawIntBits(event.getProgress()),
                        Float.floatToRawIntBits(buffer.readFloat()));
                case 3 -> assertEquals(event.getName().getString(),
                        net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC
                                .decode(buffer)
                                .getString());
                case 4 -> {
                    assertEquals(event.getColor().ordinal(), buffer.readVarInt());
                    assertEquals(event.getOverlay().ordinal(), buffer.readVarInt());
                }
                case 5 -> assertEquals(0x07, buffer.readUnsignedByte());
                default -> throw new AssertionError("unexpected action " + expectedAction);
            }
            assertFalse(buffer.isReadable());
        } finally {
            source.release();
        }
    }

    private static void assertFlags(
            int expected,
            boolean darkenScreen,
            boolean playMusic,
            boolean createWorldFog) {
        int actual = (darkenScreen ? 0x01 : 0)
                | (playMusic ? 0x02 : 0)
                | (createWorldFog ? 0x04 : 0);
        assertEquals(expected, actual);
    }

    private static void assertEquivalent(
            Id009BossEventPacketDefinition.Value expected,
            Id009BossEventPacketDefinition.Value actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.operation().getClass(), actual.operation().getClass());
        if (expected.operation() instanceof Id009BossEventPacketDefinition.Add left) {
            var right = (Id009BossEventPacketDefinition.Add) actual.operation();
            assertEquals(left.name().getString(), right.name().getString());
            assertEquals(Float.floatToRawIntBits(left.progress()), Float.floatToRawIntBits(right.progress()));
            assertEquals(left.color(), right.color());
            assertEquals(left.overlay(), right.overlay());
            assertEquals(left.darkenScreen(), right.darkenScreen());
            assertEquals(left.playMusic(), right.playMusic());
            assertEquals(left.createWorldFog(), right.createWorldFog());
        } else if (expected.operation() instanceof Id009BossEventPacketDefinition.UpdateName left) {
            var right = (Id009BossEventPacketDefinition.UpdateName) actual.operation();
            assertEquals(left.name().getString(), right.name().getString());
        } else {
            assertEquals(expected.operation(), actual.operation());
        }
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            Consumer<ByteBuf> bodyWriter) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            writeVarInt(encoded, 9);
            writeUuid(encoded, RECORDED_ID);
            bodyWriter.accept(encoded);
            assertThrows(RuntimeException.class, () -> decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static TestBossEvent event(UUID id) {
        TestBossEvent event = new TestBossEvent(
                id,
                Component.translatable("boss.test", Component.literal("nested")),
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.NOTCHED_12);
        event.setProgress(1.5F);
        event.setDarkenScreen(true);
        event.setPlayBossMusic(true);
        event.setCreateWorldFog(true);
        return event;
    }

    private static RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess()));
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                9,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 9, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundBossEventPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundBossEventPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        9,
                        new byte[] {9});
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

    private static UUID readUuid(ByteBuf buffer) {
        return new UUID(buffer.readLong(), buffer.readLong());
    }

    private static void writeUuid(ByteBuf buffer, UUID id) {
        buffer.writeLong(id.getMostSignificantBits());
        buffer.writeLong(id.getLeastSignificantBits());
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

    private static final class TestBossEvent extends BossEvent {
        private TestBossEvent(
                UUID id,
                Component name,
                BossBarColor color,
                BossBarOverlay overlay) {
            super(id, name, color, overlay);
        }
    }
}
