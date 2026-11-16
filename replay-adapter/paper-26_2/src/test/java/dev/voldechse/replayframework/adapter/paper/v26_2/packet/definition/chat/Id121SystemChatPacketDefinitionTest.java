package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.Paper26_2CaptureTestState;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id121SystemChatPacketDefinitionTest {

    private static final int PACKET_ID = 121;
    private static final String TYPE_NAME = "play.clientbound.minecraft:system_chat";
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
    void classifiesSystemChatAsConfigurableCaptureAndSafePlayback() {
        var definition = new Id121SystemChatPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x79, Id121SystemChatPacketDefinition.PACKET_ID);
        assertEquals("minecraft:system_chat", Id121SystemChatPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.CONFIGURABLE, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInChatDomainCatalogAndPaperRegistry() {
        var domainMatches = ChatPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id121SystemChatPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id121SystemChatPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.CONFIGURABLE, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void replaysServerBroadcastsUnchanged() {
        var definition = new Id121SystemChatPacketDefinition();
        ClientboundSystemChatPacket death = new ClientboundSystemChatPacket(
                Component.translatable("death.attack.fall", "Alex"), false);

        PacketRewriteResult result = definition.rewrite(death, descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(Optional.of(death), result.packet());
    }

    @Test
    void neitherRecordsNorReplaysMessagesToOnePlayer() {
        var definition = new Id121SystemChatPacketDefinition();
        var state = Paper26_2CaptureTestState.create();
        for (ClientboundSystemChatPacket privateMessage : new ClientboundSystemChatPacket[] {
            new ClientboundSystemChatPacket(
                    Component.translatable("commands.gamemode.success.self", "Creative"), false),
            new ClientboundSystemChatPacket(Component.literal("Recording started"), false),
            new ClientboundSystemChatPacket(
                    Component.translatable("death.attack.fall", "Alex"), true)
        }) {
            assertTrue(definition.suppressesRecipientState(privateMessage, state, 0L));
            assertEquals(
                    PacketRewriteResult.Decision.DROP_LOCAL,
                    definition.rewrite(privateMessage, descriptor(), rewriter()).decision());
        }
    }

    @Test
    void recordsServerBroadcastsOnceForTheWholeWorld() {
        var definition = new Id121SystemChatPacketDefinition();
        ClientboundSystemChatPacket joined = new ClientboundSystemChatPacket(
                Component.translatable("multiplayer.player.joined", "Alex"), false);

        assertFalse(definition.suppressesRecipientState(
                joined, Paper26_2CaptureTestState.create(), 0L));
        assertTrue(definition.worldWide());
        assertTrue(definition.deduplicateRecipientCopies());
    }

    @Test
    void nativeProtocolRoundTripsChatAndActionBarComponentsAndCaptureContext() {
        ProtocolInfo<?> protocol = protocol();
        Component[] components = {
            Component.empty(),
            Component.literal("System: Grüße, 雪, 😀"),
            Component.literal("Maintenance")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true))
                    .append(Component.literal(" soon").withStyle(Style.EMPTY.withItalic(true))),
            Component.translatableWithFallback(
                    "replay.system.notice", "Notice: %s", "restart")
        };

        for (Component component : components) {
            for (boolean overlay : new boolean[] {false, true}) {
                ClientboundSystemChatPacket expected = new ClientboundSystemChatPacket(component, overlay);
                ByteBuf encoded = Unpooled.buffer();
                try {
                    encode(protocol, encoded, expected);
                    ByteBuf header = encoded.duplicate();
                    assertEquals(PACKET_ID, VarInt.read(header));

                    ClientboundSystemChatPacket decoded = assertInstanceOf(
                            ClientboundSystemChatPacket.class, decode(protocol, encoded));
                    assertEquals(component, decoded.content());
                    assertEquals(overlay, decoded.overlay());
                    assertFalse(encoded.isReadable());

                    CaptureContext context = new Id121SystemChatPacketDefinition().captureContext(
                            decoded, descriptor(), null,
                            Paper26_2CaptureTestState.create(), 0L);
                    assertEquals(Optional.of(component.getString()), context.normalizedChat());
                    assertEquals(Optional.of(PacketDisposition.CONFIGURABLE), context.disposition());
                } finally {
                    encoded.release();
                }
            }
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
                PacketDisposition.CONFIGURABLE,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
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
