package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id106SetObjectivePacketDefinitionTest {

    private static final int PACKET_ID = 106;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_objective";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000161");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000162");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesObjectiveMutationsAsCapturedViewerLocalState() {
        var definition = new Id106SetObjectivePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x6A, Id106SetObjectivePacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_objective", Id106SetObjectivePacketDefinition.IDENTIFIER);
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
        assertInstanceOf(Id106SetObjectivePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id106SetObjectivePacketDefinition.class, catalogMatches.getFirst());

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
    void nativeCodecRoundTripsAddAndChangeWithEveryRenderTypeAndNumberFormat() {
        ProtocolInfo<?> protocol = protocol();
        List<NumberFormat> numberFormats = List.of(
                BlankFormat.INSTANCE,
                new StyledFormat(Style.EMPTY.withColor(net.minecraft.ChatFormatting.GOLD)),
                new FixedFormat(Component.literal("fixed score: ✦")));

        for (int method : List.of(ClientboundSetObjectivePacket.METHOD_ADD,
                ClientboundSetObjectivePacket.METHOD_CHANGE)) {
            for (ObjectiveCriteria.RenderType renderType : ObjectiveCriteria.RenderType.values()) {
                assertNativeRoundTrip(protocol, packet(
                        "objective_" + method + "_" + renderType.getId(),
                        method,
                        Component.literal("Display: 玩家 / élève"),
                        renderType,
                        null));
                for (NumberFormat numberFormat : numberFormats) {
                    assertNativeRoundTrip(protocol, packet(
                            "objective_" + method + "_" + renderType.getId(),
                            method,
                            Component.literal("Display: 玩家 / élève"),
                            renderType,
                            numberFormat));
                }
            }
        }
    }

    @Test
    void removeModeCarriesOnlyTheNameAndDecodesDefaultFields() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetObjectivePacket remove = packet(
                "remove me", ClientboundSetObjectivePacket.METHOD_REMOVE,
                Component.literal("ignored display"), ObjectiveCriteria.RenderType.HEARTS,
                BlankFormat.INSTANCE);
        ByteBuf encoded = encodePacket(protocol, remove);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals("remove me", fields.readUtf());
            assertEquals(ClientboundSetObjectivePacket.METHOD_REMOVE, fields.readByte());
            assertFalse(fields.isReadable());

            ClientboundSetObjectivePacket decoded = assertInstanceOf(
                    ClientboundSetObjectivePacket.class, decode(protocol, encoded));
            assertEquals("remove me", decoded.getObjectiveName());
            assertEquals(ClientboundSetObjectivePacket.METHOD_REMOVE, decoded.getMethod());
            assertEquals(Component.empty(), decoded.getDisplayName());
            assertEquals(ObjectiveCriteria.RenderType.INTEGER, decoded.getRenderType());
            assertEquals(Optional.empty(), decoded.getNumberFormat());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecUsesTheVersionMatchedPacketIdAndConditionalWireShape() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetObjectivePacket add = packet(
                "kills", ClientboundSetObjectivePacket.METHOD_ADD,
                Component.literal("Eliminations"), ObjectiveCriteria.RenderType.HEARTS,
                new FixedFormat(Component.literal("★")));

        ByteBuf encoded = encodePacket(protocol, add);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(0x6A, fields.readVarInt());
            assertEquals("kills", fields.readUtf());
            assertEquals(ClientboundSetObjectivePacket.METHOD_ADD, fields.readByte());
            assertTrue(fields.isReadable(), "add/change modes must carry conditional fields");
            assertEquals(GamePacketTypes.CLIENTBOUND_SET_OBJECTIVE, add.type());

            ClientboundSetObjectivePacket decoded = assertInstanceOf(
                    ClientboundSetObjectivePacket.class, decode(protocol, encoded));
            assertEquals("Eliminations", decoded.getDisplayName().getString());
            assertEquals(ObjectiveCriteria.RenderType.HEARTS, decoded.getRenderType());
            assertEquals(Optional.of(new FixedFormat(Component.literal("★"))),
                    decoded.getNumberFormat());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedPayloadForAllDefinedModes() {
        ProtocolInfo<?> protocol = protocol();
        List<ClientboundSetObjectivePacket> packets = List.of(
                packet("add", ClientboundSetObjectivePacket.METHOD_ADD,
                        Component.literal("display"), ObjectiveCriteria.RenderType.INTEGER,
                        new StyledFormat(Style.EMPTY)),
                packet("remove", ClientboundSetObjectivePacket.METHOD_REMOVE,
                        Component.empty(), ObjectiveCriteria.RenderType.INTEGER, null),
                packet("change", ClientboundSetObjectivePacket.METHOD_CHANGE,
                        Component.literal("changed"), ObjectiveCriteria.RenderType.HEARTS,
                        new FixedFormat(Component.literal("points"))));

        for (ClientboundSetObjectivePacket packet : packets) {
            byte[] body = body(protocol, packet);
            for (int length = 0; length < body.length; length++) {
                assertNativeDecodeFails(
                        protocol,
                        java.util.Arrays.copyOf(body, length),
                        "truncated method " + packet.getMethod() + " body at " + length
                                + " of " + body.length + " bytes");
            }
        }
        assertNativeDecodeFails(protocol, new byte[] {(byte) 0x80});
        assertNativeDecodeFails(protocol,
                new byte[] {0, ClientboundSetObjectivePacket.METHOD_ADD},
                "add objective missing its display component");
    }

    @Test
    void unknownMethodUsesPaperDecoderRemoveShapedFallback() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            new FriendlyByteBuf(wire).writeUtf("future method").writeByte(3);

            ClientboundSetObjectivePacket decoded = assertInstanceOf(
                    ClientboundSetObjectivePacket.class, decode(protocol, wire));
            assertEquals("future method", decoded.getObjectiveName());
            assertEquals(3, decoded.getMethod());
            assertEquals(Component.empty(), decoded.getDisplayName());
            assertEquals(ObjectiveCriteria.RenderType.INTEGER, decoded.getRenderType());
            assertEquals(Optional.empty(), decoded.getNumberFormat());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void playbackDropsValidAndMalformedPayloadsBeforeCodecAccess() {
        var definition = new Id106SetObjectivePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : List.of(
                body(protocol, packet("new", ClientboundSetObjectivePacket.METHOD_ADD,
                        Component.literal("name"), ObjectiveCriteria.RenderType.INTEGER, null)),
                body(protocol, packet("remove", ClientboundSetObjectivePacket.METHOD_REMOVE,
                        Component.empty(), ObjectiveCriteria.RenderType.INTEGER, null)),
                new byte[0],
                new byte[] {(byte) 0x80},
                new byte[] {1, 0, (byte) 0x80})) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingThePacket() {
        PacketRewriteResult result = new Id106SetObjectivePacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id106SetObjectivePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND,
                        false, PacketDescriptor.PlaybackScope.UNSAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.CONFIGURATION,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        true, PacketDescriptor.PlaybackScope.VIEWER_LOCAL));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetObjectivePacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(expected.getObjectiveName(), fields.readUtf());
            assertEquals(expected.getMethod(), fields.readByte());

            ClientboundSetObjectivePacket decoded = assertInstanceOf(
                    ClientboundSetObjectivePacket.class, decode(protocol, encoded));
            assertEquals(expected.getObjectiveName(), decoded.getObjectiveName());
            assertEquals(expected.getMethod(), decoded.getMethod());
            if (expected.getMethod() == ClientboundSetObjectivePacket.METHOD_ADD
                    || expected.getMethod() == ClientboundSetObjectivePacket.METHOD_CHANGE) {
                assertEquals(expected.getDisplayName().getString(),
                        decoded.getDisplayName().getString());
                assertEquals(expected.getRenderType(), decoded.getRenderType());
                assertEquals(expected.getNumberFormat(), decoded.getNumberFormat());
            } else {
                assertEquals(Component.empty(), decoded.getDisplayName());
                assertEquals(ObjectiveCriteria.RenderType.INTEGER, decoded.getRenderType());
                assertEquals(Optional.empty(), decoded.getNumberFormat());
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

    private static ClientboundSetObjectivePacket packet(
            String objectiveName,
            int method,
            Component displayName,
            ObjectiveCriteria.RenderType renderType,
            NumberFormat numberFormat) {
        Objective objective = new Objective(
                new Scoreboard(),
                objectiveName,
                ObjectiveCriteria.DUMMY,
                displayName,
                renderType,
                false,
                numberFormat);
        return new ClientboundSetObjectivePacket(objective, method);
    }

    private static byte[] body(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            ByteBuf body = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(body));
            return bytes(body);
        } finally {
            encoded.release();
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
        assertNativeDecodeFails(protocol, body, "malformed packet body");
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol, byte[] body, String description) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire), description);
        } finally {
            wire.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        boolean clientbound = direction == PacketDescriptor.Direction.CLIENTBOUND;
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                clientbound ? PacketDisposition.STATEFUL : PacketDisposition.CONTROL,
                clientbound,
                clientbound && replayable,
                false,
                "paper-26.2/" + typeName,
                clientbound ? scope : PacketDescriptor.PlaybackScope.UNSAFE);
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
                throw new AssertionError("viewer-local objective mutation must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local objective mutation must not be decoded");
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
