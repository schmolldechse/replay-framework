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
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id015And016CommandPacketDefinitionsTest {

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
    void classifiesBothCommandPacketContracts() {
        assertEquals(0x0F, Id015CommandSuggestionsPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:command_suggestions",
                Id015CommandSuggestionsPacketDefinition.IDENTIFIER);
        assertEquals(0x10, Id016CommandsPacketDefinition.PACKET_ID);
        assertEquals("minecraft:commands", Id016CommandsPacketDefinition.IDENTIFIER);
        assertClassification(
                new Id015CommandSuggestionsPacketDefinition(),
                15,
                0x0F,
                "play.clientbound.minecraft:command_suggestions",
                PacketDisposition.EPHEMERAL);
        assertClassification(
                new Id016CommandsPacketDefinition(),
                16,
                0x10,
                "play.clientbound.minecraft:commands",
                PacketDisposition.STATEFUL);
    }

    @Test
    void dropsEveryRecordedPayloadBeforeDecodeOrEncode() {
        for (PacketCase packetCase : packetCases()) {
            AtomicInteger decodes = new AtomicInteger();
            AtomicInteger encodes = new AtomicInteger();
            PacketCodec<Object> codec = failingCodec(packetCase.identifier(), decodes, encodes);

            for (byte[] payload : List.of(
                    new byte[0],
                    new byte[] {0},
                    new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                    new byte[] {1, 2, 3, 4, 5, 6, 7, 8})) {
                PacketRewriteResult result = packetCase.definition().handlePlayback(
                        frame(packetCase.packetId(), payload),
                        descriptor(packetCase),
                        codec,
                        rewriter());

                assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
                assertTrue(result.packet().isEmpty());
                assertEquals(
                        "recorded connection state does not belong to the observer",
                        result.reason());
            }

            assertEquals(0, decodes.get());
            assertEquals(0, encodes.get());
        }
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        for (PacketCase packetCase : packetCases()) {
            PacketRewriteResult result = packetCase.definition().rewrite(
                    new Object(), descriptor(packetCase), rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }
    }

    @Test
    void rejectsDescriptorMismatchesAndNullBoundaries() {
        for (PacketCase packetCase : packetCases()) {
            PacketCodec<Object> codec = failingCodec(
                    packetCase.identifier(), new AtomicInteger(), new AtomicInteger());
            PacketDescriptor valid = descriptor(packetCase);
            List<PacketDescriptor> mismatches = List.of(
                    descriptor(packetCase, "play.clientbound.minecraft:other",
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId() + 1, PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.CONFIGURATION,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.SERVERBOUND, false,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, false,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.SAFE));

            for (PacketDescriptor mismatch : mismatches) {
                assertThrows(IncompatibleAdapterException.class, () ->
                        packetCase.definition().handlePlayback(
                                frame(packetCase.packetId(), new byte[0]),
                                mismatch,
                                codec,
                                rewriter()));
            }

            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(null, valid, codec, rewriter()));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(
                            frame(packetCase.packetId(), new byte[0]), null, codec, rewriter()));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(
                            frame(packetCase.packetId(), new byte[0]), valid, null, rewriter()));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(
                            frame(packetCase.packetId(), new byte[0]), valid, codec, null));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().rewrite(null, valid, rewriter()));
        }
    }

    @Test
    void nativeSuggestionsCodecCoversRangesEntriesTooltipsAndUtf8() {
        ProtocolInfo<?> protocol = protocol();
        List<ClientboundCommandSuggestionsPacket> packets = List.of(
                new ClientboundCommandSuggestionsPacket(0, 0, 0, List.of()),
                new ClientboundCommandSuggestionsPacket(
                        27,
                        4,
                        3,
                        List.of(new ClientboundCommandSuggestionsPacket.Entry(
                                "über", Optional.empty()))),
                new ClientboundCommandSuggestionsPacket(
                        Integer.MAX_VALUE,
                        1,
                        5,
                        List.of(
                                new ClientboundCommandSuggestionsPacket.Entry(
                                        "first", Optional.of(Component.literal("First tooltip"))),
                                new ClientboundCommandSuggestionsPacket.Entry(
                                        "second", Optional.of(Component.literal("§aSecond tooltip"))))),
                new ClientboundCommandSuggestionsPacket(
                        Integer.MIN_VALUE,
                        -1,
                        -2,
                        List.of(new ClientboundCommandSuggestionsPacket.Entry(
                                "native signed values", Optional.empty()))));

        for (ClientboundCommandSuggestionsPacket packet : packets) {
            ByteBuf encoded = encode(protocol, packet);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf wireView = encoded.duplicate();
                assertEquals(0x0F, VarInt.read(wireView));

                ClientboundCommandSuggestionsPacket decoded = assertInstanceOf(
                        ClientboundCommandSuggestionsPacket.class, decode(protocol, encoded));
                assertFalse(encoded.isReadable());
                assertEquals(packet.id(), decoded.id());
                assertEquals(packet.start(), decoded.start());
                assertEquals(packet.length(), decoded.length());
                assertEquals(packet.suggestions().size(), decoded.suggestions().size());
                for (int index = 0; index < packet.suggestions().size(); index++) {
                    var expected = packet.suggestions().get(index);
                    var actual = decoded.suggestions().get(index);
                    assertEquals(expected.text(), actual.text());
                    assertEquals(expected.tooltip().isPresent(), actual.tooltip().isPresent());
                    if (expected.tooltip().isPresent()) {
                        assertEquals(
                                expected.tooltip().orElseThrow().getString(),
                                actual.tooltip().orElseThrow().getString());
                    }
                }

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
    void nativeSuggestionsCodecRejectsTruncationAndLeavesFramingBytes() {
        ProtocolInfo<?> protocol = protocol();
        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {1},
                new byte[] {1, 0},
                new byte[] {1, 0, 0},
                new byte[] {1, 0, 0, 1},
                new byte[] {1, 0, 0, 1, 3, 'a'})) {
            ByteBuf malformed = Unpooled.buffer();
            try {
                VarInt.write(malformed, 0x0F);
                malformed.writeBytes(payload);
                assertThrows(RuntimeException.class, () -> decode(protocol, malformed));
            } finally {
                malformed.release();
            }
        }

        ByteBuf completeTooltip = encode(
                protocol,
                new ClientboundCommandSuggestionsPacket(
                        4,
                        0,
                        1,
                        List.of(new ClientboundCommandSuggestionsPacket.Entry(
                                "value", Optional.of(Component.literal("tooltip"))))));
        ByteBuf truncatedTooltip = completeTooltip.copy(
                completeTooltip.readerIndex(), completeTooltip.readableBytes() - 1);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, truncatedTooltip));
        } finally {
            truncatedTooltip.release();
            completeTooltip.release();
        }

        ByteBuf trailing = encode(
                protocol, new ClientboundCommandSuggestionsPacket(3, 0, 0, List.of()));
        try {
            trailing.writeByte(0x7A);
            assertInstanceOf(ClientboundCommandSuggestionsPacket.class, decode(protocol, trailing));
            assertEquals(0x7A, trailing.readUnsignedByte());
            assertFalse(trailing.isReadable());
        } finally {
            trailing.release();
        }
    }

    @Test
    void nativeCommandsCodecCoversEveryNodeAndFlagFamily() {
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
    void nativeCommandsCodecRejectsTruncationAndImpossibleGraphs() {
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

    private static void assertClassification(
            PacketDefinition<Paper26_2ReplayPacketRewriter> definition,
            int decimalId,
            int hexadecimalId,
            String typeName,
            PacketDisposition disposition) {
        assertEquals(decimalId, definition.packetId());
        assertEquals(hexadecimalId, definition.packetId());
        assertEquals(typeName, definition.typeName());
        assertEquals(disposition, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    private static List<PacketCase> packetCases() {
        return List.of(
                new PacketCase(
                        new Id015CommandSuggestionsPacketDefinition(),
                        15,
                        "minecraft:command_suggestions",
                        "play.clientbound.minecraft:command_suggestions",
                        PacketDisposition.EPHEMERAL),
                new PacketCase(
                        new Id016CommandsPacketDefinition(),
                        16,
                        "minecraft:commands",
                        "play.clientbound.minecraft:commands",
                        PacketDisposition.STATEFUL));
    }

    private static PacketDescriptor descriptor(PacketCase packetCase) {
        return descriptor(
                packetCase,
                packetCase.typeName(),
                packetCase.packetId(),
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            PacketCase packetCase,
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
                packetCase.disposition(),
                clientbound,
                replayable,
                false,
                "paper-26.2/" + packetCase.identifier(),
                scope);
    }

    private static RawPacketFrame frame(int packetId, byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, packetId, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> failingCodec(
            String identifier,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local " + identifier + " must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local " + identifier + " must not decode");
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
            encodeInto(protocol, buffer, packet);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeInto(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private record PacketCase(
            PacketDefinition<Paper26_2ReplayPacketRewriter> definition,
            int packetId,
            String identifier,
            String typeName,
            PacketDisposition disposition) {}
}
