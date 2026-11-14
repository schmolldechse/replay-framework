package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id098SetDisplayObjectivePacketDefinitionTest {

    private static final int PACKET_ID = 98;
    private static final String TYPE_NAME =
            "play.clientbound.minecraft:set_display_objective";
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
    void classifiesDisplaySelectionAsCapturedViewerLocalState() {
        var definition = new Id098SetDisplayObjectivePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x62, Id098SetDisplayObjectivePacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_display_objective",
                Id098SetDisplayObjectivePacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = ScoreboardPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id098SetDisplayObjectivePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id098SetDisplayObjectivePacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
    }

    @Test
    void nativeProtocolPreservesAllNineteenSlotsAndTheClearSentinel() {
        ProtocolInfo<?> protocol = protocol();

        assertEquals(19, DisplaySlot.values().length);
        for (DisplaySlot slot : DisplaySlot.values()) {
            assertEquals(slot.ordinal(), slot.id());
            assertNativeRoundTrip(protocol, packet(slot, "objective_" + slot.id()),
                    "objective_" + slot.id());
            assertNativeRoundTrip(protocol, packet(slot, null), null);
        }
    }

    @Test
    void nativeProtocolPreservesObjectiveNameEncodingAndMaximumLength() {
        ProtocolInfo<?> protocol = protocol();
        String[] names = {
            "kills",
            " objective with spaces ",
            "§aRang: 玩家 / élève",
            "x".repeat(32_767)
        };

        for (String name : names) {
            assertNativeRoundTrip(protocol, packet(DisplaySlot.SIDEBAR, name), name);
        }

        ByteBuf overlong = Unpooled.buffer();
        try {
            VarInt.write(overlong, PACKET_ID);
            VarInt.write(overlong, DisplaySlot.SIDEBAR.id());
            byte[] tooLong = "x".repeat(32_768).getBytes(StandardCharsets.UTF_8);
            VarInt.write(overlong, tooLong.length);
            overlong.writeBytes(tooLong);
            assertThrows(RuntimeException.class, () -> decode(protocol, overlong));
        } finally {
            overlong.release();
        }
    }

    @Test
    void nativeWireOrderIsVarIntSlotThenUtfObjectiveName() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetDisplayObjectivePacket expected =
                packet(DisplaySlot.TEAM_DARK_PURPLE, "team_scoreboard");
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(DisplaySlot.TEAM_DARK_PURPLE.id(), fields.readVarInt());
            assertEquals("team_scoreboard", fields.readUtf());
            assertFalse(fields.isReadable());
            assertEquals(GamePacketTypes.CLIENTBOUND_SET_DISPLAY_OBJECTIVE, expected.type());
        } finally {
            encoded.release();
        }
    }

    @Test
    void unknownSlotIdsUsePaper26_2ZeroValueFallback() {
        ProtocolInfo<?> protocol = protocol();

        for (int slotId : new int[] {19, 127, Integer.MAX_VALUE, -1}) {
            ByteBuf wire = Unpooled.buffer();
            try {
                VarInt.write(wire, PACKET_ID);
                VarInt.write(wire, slotId);
                new FriendlyByteBuf(wire).writeUtf("fallback_objective");

                ClientboundSetDisplayObjectivePacket decoded = assertInstanceOf(
                        ClientboundSetDisplayObjectivePacket.class, decode(protocol, wire));
                assertEquals(DisplaySlot.LIST, decoded.getSlot());
                assertEquals("fallback_objective", decoded.getObjectiveName());
                assertFalse(wire.isReadable());
            } finally {
                wire.release();
            }
        }
    }

    @Test
    void nativeCodecRejectsEveryTruncatedBodyAndMalformedFields() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol,
                packet(DisplaySlot.BELOW_NAME, "truncation_target"));

        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol,
                    java.util.Arrays.copyOf(completeBody, length));
        }
        assertNativeDecodeFails(protocol, new byte[] {(byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {1, 10, 'x'});

        ByteBuf invalidSlotVarInt = Unpooled.buffer();
        try {
            VarInt.write(invalidSlotVarInt, PACKET_ID);
            for (int index = 0; index < 6; index++) {
                invalidSlotVarInt.writeByte(0x80);
            }
            assertThrows(RuntimeException.class, () -> decode(protocol, invalidSlotVarInt));
        } finally {
            invalidSlotVarInt.release();
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(protocol,
                packet(DisplaySlot.TEAM_WHITE, "team_white_objective"));
        try {
            wire.writeByte(0x7A);
            ClientboundSetDisplayObjectivePacket decoded = assertInstanceOf(
                    ClientboundSetDisplayObjectivePacket.class, decode(protocol, wire));
            assertEquals(DisplaySlot.TEAM_WHITE, decoded.getSlot());
            assertEquals("team_white_objective", decoded.getObjectiveName());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void playbackDropsValidAndMalformedPayloadsBeforeCodecAccess() {
        var definition = new Id098SetDisplayObjectivePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : new byte[][] {
            nativePayload(packet(DisplaySlot.LIST, "tab_objective")),
            nativePayload(packet(DisplaySlot.SIDEBAR, null)),
            new byte[0],
            {(byte) 0x80},
            {1, 10, 'x'}
        }) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
            assertEquals("recorded connection state does not belong to the observer",
                    result.reason());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingPacketType() {
        PacketRewriteResult result = new Id098SetDisplayObjectivePacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void validatesDescriptorBeforeApplyingViewerLocalPolicy() {
        var definition = new Id098SetDisplayObjectivePacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        PacketDescriptor[] mismatches = {
            descriptor("play.clientbound.minecraft:other", PACKET_ID,
                    PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                    PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true),
            descriptor(TYPE_NAME, PACKET_ID + 1,
                    PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                    PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true),
            descriptor(TYPE_NAME, PACKET_ID,
                    PacketDescriptor.PlaybackScope.SAFE,
                    PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true),
            descriptor(TYPE_NAME, PACKET_ID,
                    PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                    PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true),
            new PacketDescriptor(
                    TYPE_NAME,
                    PacketPhase.PLAY,
                    PacketDescriptor.Direction.SERVERBOUND,
                    PACKET_ID,
                    PacketDisposition.STATEFUL,
                    false,
                    false,
                    false,
                    "paper-26.2/" + TYPE_NAME,
                    PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            descriptor(TYPE_NAME, PACKET_ID,
                    PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                    PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false)
        };

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
        }

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), descriptor(), codec, null));
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundSetDisplayObjectivePacket expected,
            String objectiveName) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(expected.getSlot().id(), fields.readVarInt());
            assertEquals(objectiveName == null ? "" : objectiveName, fields.readUtf());
            assertFalse(fields.isReadable());

            ClientboundSetDisplayObjectivePacket decoded = assertInstanceOf(
                    ClientboundSetDisplayObjectivePacket.class, decode(protocol, encoded));
            assertEquals(expected.getSlot(), decoded.getSlot());
            if (objectiveName == null) {
                assertNull(decoded.getObjectiveName());
            } else {
                assertEquals(objectiveName, decoded.getObjectiveName());
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

    private static ClientboundSetDisplayObjectivePacket packet(
            DisplaySlot slot, String objectiveName) {
        Objective objective = objectiveName == null ? null : new Objective(
                new Scoreboard(),
                objectiveName,
                ObjectiveCriteria.DUMMY,
                Component.literal("Recorded objective"),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null);
        return new ClientboundSetDisplayObjectivePacket(slot, objective);
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

    private static byte[] nativePayload(ClientboundSetDisplayObjectivePacket packet) {
        return body(protocol(), packet);
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
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketDescriptor.PlaybackScope scope,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable) {
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                false,
                "paper-26.2/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local display update must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local display update must not be decoded");
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
