package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id068PlayerCombatKillPacketDefinitionTest {

    private static final int PACKET_ID = 0x44;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_combat_kill";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int[] PLAYER_IDS = {
        0, 1, 127, 128, 999, Integer.MAX_VALUE, Integer.MIN_VALUE, -1
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAndRegistersCombatKillAsViewerLocalEphemeralData() {
        var definition = new Id068PlayerCombatKillPacketDefinition();

        assertEquals(68, definition.packetId());
        assertEquals(PACKET_ID, Id068PlayerCombatKillPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:player_combat_kill",
                Id068PlayerCombatKillPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        assertEquals(1, PlayerPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
        assertEquals(1, Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .count());
        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
    }

    @Test
    void dropsEmptyValidLookingAndMalformedPayloadsBeforeCodecUse() {
        var definition = new Id068PlayerCombatKillPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                nativePayload(packet(101, CommonComponents.EMPTY)),
                nativePayload(packet(101, Component.literal("Death message"))),
                new byte[] {(byte) 0x80},
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80,
                    (byte) 0x80, (byte) 0x80, (byte) 0x80},
                new byte[] {0, 1, 2, 3})) {
            assertDropBeforeCodec(definition, codec, payload);
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsVictimDeathScreenWithoutInspectingThePacket() {
        PacketRewriteResult result = new Id068PlayerCombatKillPacketDefinition()
                .rewrite(packet(101, Component.literal("Death message")), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id068PlayerCombatKillPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
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
            assertThrows(IncompatibleAdapterException.class, () ->
                    definition.rewrite(packet(101, Component.empty()), mismatch, rewriter()));
        }
    }

    @Test
    void nativeProtocolRoundTripsPlayerIdsAndDeathMessageComponentVariants() {
        ProtocolInfo<?> protocol = protocol();
        List<Component> messages = deathMessages();

        for (int playerId : PLAYER_IDS) {
            for (Component message : messages) {
                ClientboundPlayerCombatKillPacket expected = packet(playerId, message);
                ByteBuf encoded = encodePacket(protocol, expected);
                try {
                    byte[] expectedWire = bytes(encoded);
                    ByteBuf wireView = encoded.duplicate();
                    assertEquals(PACKET_ID, VarInt.read(wireView));
                    assertEquals(playerId, VarInt.read(wireView));

                    Packet<?> decoded = decode(protocol, encoded);
                    ClientboundPlayerCombatKillPacket decodedPacket = assertInstanceOf(
                            ClientboundPlayerCombatKillPacket.class, decoded);
                    assertEquals(playerId, decodedPacket.playerId());
                    assertEquals(expected.message().getString(), decodedPacket.message().getString());
                    assertTrue(decoded.isSkippable());
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
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedPacketBodyPrefixAndMalformedPlayerVarInt() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = nativePayload(packet(
                128,
                Component.translatable("death.attack.mob", Component.literal("Victim"))));

        for (int length = 0; length < completeBody.length; length++) {
            ByteBuf truncated = Unpooled.buffer();
            try {
                VarInt.write(truncated, PACKET_ID);
                truncated.writeBytes(completeBody, 0, length);
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }

        ByteBuf malformedPlayerId = Unpooled.buffer();
        try {
            VarInt.write(malformedPlayerId, PACKET_ID);
            malformedPlayerId.writeBytes(new byte[] {
                (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, (byte) 0x80
            });
            assertThrows(RuntimeException.class, () -> decode(protocol, malformedPlayerId));
        } finally {
            malformedPlayerId.release();
        }
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerCombatKillPacket expected = packet(
                101, Component.translatable("death.attack.generic", Component.literal("Victim")));
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            encoded.writeByte(0x7A);
            ClientboundPlayerCombatKillPacket decoded = assertInstanceOf(
                    ClientboundPlayerCombatKillPacket.class, decode(protocol, encoded));
            assertEquals(expected.playerId(), decoded.playerId());
            assertEquals(expected.message().getString(), decoded.message().getString());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static List<Component> deathMessages() {
        Component longTextFallback = Component.translatable(
                "death.attack.message_too_long",
                Component.literal("d".repeat(256)).withStyle(ChatFormatting.YELLOW));
        Component oversizedMessageFallback = Component.translatable(
                        "death.attack.even_more_magic", Component.literal("ReplayVictim"))
                .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(longTextFallback)));
        Component styledDeathMessage = Component.literal("ReplayVictim was slain")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_RED)
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.translatable("death.attack.generic"))));

        return List.of(
                CommonComponents.EMPTY,
                Component.literal(""),
                Component.literal("ReplayVictim died: Grüße, 雪, 😀"),
                Component.translatable(
                        "death.attack.mob",
                        Component.literal("ReplayVictim"),
                        Component.literal("Skeleton")),
                styledDeathMessage,
                oversizedMessageFallback);
    }

    private static ClientboundPlayerCombatKillPacket packet(int playerId, Component message) {
        return new ClientboundPlayerCombatKillPacket(playerId, message);
    }

    private static void assertDropBeforeCodec(
            Id068PlayerCombatKillPacketDefinition definition,
            PacketCodec<Object> codec,
            byte[] payload) {
        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    private static byte[] nativePayload(ClientboundPlayerCombatKillPacket packet) {
        ByteBuf encoded = encodePacket(protocol(), packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
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
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.EPHEMERAL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
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
                throw new AssertionError("viewer-local combat kill must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local combat kill must not decode");
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
