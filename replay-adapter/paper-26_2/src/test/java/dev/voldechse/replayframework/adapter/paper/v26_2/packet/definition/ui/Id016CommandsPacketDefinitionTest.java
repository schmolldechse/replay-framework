package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id016CommandsPacketDefinitionTest {

    private static final int PACKET_ID = 16;
    private static final String IDENTIFIER = "minecraft:commands";
    private static final String TYPE_NAME = "play.clientbound.minecraft:commands";
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
    void classifiesCommandTreeAsViewerLocalStatefulData() {
        var definition = new Id016CommandsPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x10, Id016CommandsPacketDefinition.PACKET_ID);
        assertEquals(IDENTIFIER, Id016CommandsPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInUiDomainCatalogAndPaperRegistry() {
        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id016CommandsPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id016CommandsPacketDefinition.class, catalogMatches.getFirst());

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
    void dropsEveryRecordedPayloadBeforeDecodeOrEncode() {
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {0},
                new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8})) {
            PacketRewriteResult result = new Id016CommandsPacketDefinition()
                    .handlePlayback(frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
            assertEquals(
                    "recorded connection state does not belong to the observer", result.reason());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        PacketRewriteResult result = new Id016CommandsPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesAndNullBoundaries() {
        var definition = new Id016CommandsPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        PacketDescriptor valid = descriptor();
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
        }

        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, valid, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), null, codec, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), valid, null, rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(frame(new byte[0]), valid, codec, null));
        assertThrows(NullPointerException.class, () ->
                definition.rewrite(null, valid, rewriter()));
    }

    @Test
    void nativeCodecCoversEveryNodeAndFlagFamily() {
        ProtocolInfo<?> protocol = protocol();

        for (ClientboundCommandsPacket packet : List.of(
                commandsPacket(false),
                commandsPacket(true))) {
            ByteBuf encoded = encode(protocol, packet);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf wireView = encoded.duplicate();
                assertEquals(0x10, VarInt.read(wireView));

                ClientboundCommandsPacket decoded = assertInstanceOf(
                        ClientboundCommandsPacket.class, decode(protocol, encoded));
                assertFalse(encoded.isReadable());

                ByteBuf reencoded = encode(protocol, decoded);
                try {
                    assertArrayEquals(expectedWire, bytes(reencoded));
                } finally {
                    reencoded.release();
                }
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeCodecRejectsTruncationAndImpossibleGraphs() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf missing = Unpooled.buffer();
        try {
            VarInt.write(missing, 0x10);
            assertThrows(RuntimeException.class, () -> decode(protocol, missing));
        } finally {
            missing.release();
        }

        ByteBuf impossible = Unpooled.buffer();
        try {
            VarInt.write(impossible, 0x10);
            VarInt.write(impossible, 1);
            impossible.writeByte(0);
            VarInt.write(impossible, 1);
            VarInt.write(impossible, 0);
            VarInt.write(impossible, 0);
            RuntimeException failure =
                    assertThrows(RuntimeException.class, () -> decode(protocol, impossible));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
        } finally {
            impossible.release();
        }

        ByteBuf trailing = encode(protocol, commandsPacket(true));
        try {
            trailing.writeByte(0x6B);
            assertInstanceOf(ClientboundCommandsPacket.class, decode(protocol, trailing));
            assertEquals(0x6B, trailing.readUnsignedByte());
            assertFalse(trailing.isReadable());
        } finally {
            trailing.release();
        }
    }

    private static ClientboundCommandsPacket commandsPacket(boolean fullTree) {
        RootCommandNode<Object> root = new RootCommandNode<>();
        if (!fullTree) {
            return new ClientboundCommandsPacket(root, inspector(null, null));
        }

        LiteralCommandNode<Object> calculate = LiteralArgumentBuilder.<Object>literal("calculate")
                .executes(context -> 1)
                .build();
        ArgumentCommandNode<Object, Integer> amount =
                RequiredArgumentBuilder.<Object, Integer>argument(
                                "amount", IntegerArgumentType.integer(-5, 12))
                        .suggests((context, builder) -> builder.suggest(7).buildFuture())
                        .executes(context -> 1)
                        .build();
        calculate.addChild(amount);
        ArgumentCommandNode<Object, Boolean> enabled =
                RequiredArgumentBuilder.<Object, Boolean>argument(
                                "enabled", BoolArgumentType.bool())
                        .build();
        calculate.addChild(enabled);
        LiteralCommandNode<Object> alias = LiteralArgumentBuilder.<Object>literal("calc")
                .redirect(calculate)
                .build();
        root.addChild(calculate);
        root.addChild(alias);
        return new ClientboundCommandsPacket(root, inspector(amount, calculate));
    }

    private static ClientboundCommandsPacket.NodeInspector<Object> inspector(
            ArgumentCommandNode<Object, Integer> suggested,
            LiteralCommandNode<Object> restricted) {
        return new ClientboundCommandsPacket.NodeInspector<>() {
            @Override
            public Identifier suggestionId(ArgumentCommandNode<Object, ?> node) {
                return node == suggested ? Identifier.parse("minecraft:ask_server") : null;
            }

            @Override
            public boolean isExecutable(CommandNode<Object> node) {
                return node.getCommand() != null;
            }

            @Override
            public boolean isRestricted(CommandNode<Object> node) {
                return node == restricted;
            }
        };
    }

    private static PacketDescriptor descriptor() {
        return descriptor(
                TYPE_NAME,
                PACKET_ID,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                true,
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
                PacketDisposition.STATEFUL,
                clientbound,
                replayable,
                false,
                "paper-26.2/" + IDENTIFIER,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local commands must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local commands must not decode");
            }
        };
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static ByteBuf encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            StreamCodec<Object, Object> codec = (StreamCodec) protocol.codec();
            codec.encode(buffer, packet);
            return buffer;
        } catch (Throwable failure) {
            buffer.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
    }

    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamCodec<Object, Object> codec = (StreamCodec) protocol.codec();
        return (Packet<?>) codec.decode(buffer);
    }
}
