package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id038GameEventPacketDefinitionTest {

    private static final int PACKET_ID = 38;
    private static final String TYPE_NAME = "play.clientbound.minecraft:game_event";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final List<ClientboundGameEventPacket.Type> ALL_EVENTS = List.of(
            ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE,
            ClientboundGameEventPacket.START_RAINING,
            ClientboundGameEventPacket.STOP_RAINING,
            ClientboundGameEventPacket.CHANGE_GAME_MODE,
            ClientboundGameEventPacket.WIN_GAME,
            ClientboundGameEventPacket.DEMO_EVENT,
            ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND,
            ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
            ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,
            ClientboundGameEventPacket.PUFFER_FISH_STING,
            ClientboundGameEventPacket.GUARDIAN_ELDER_EFFECT,
            ClientboundGameEventPacket.IMMEDIATE_RESPAWN,
            ClientboundGameEventPacket.LIMITED_CRAFTING,
            ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START);
    private static final Set<ClientboundGameEventPacket.Type> WEATHER_EVENTS = Set.of(
            ClientboundGameEventPacket.START_RAINING,
            ClientboundGameEventPacket.STOP_RAINING,
            ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
            ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE);
    private static final List<ClientboundGameEventPacket.Type> WEATHER_LEVEL_EVENTS = List.of(
            ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,
            ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE);

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesGameEventsAsReplayableEphemeralSafeData() {
        var definition = new Id038GameEventPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x26, Id038GameEventPacketDefinition.PACKET_ID);
        assertEquals("minecraft:game_event", Id038GameEventPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.SAFE,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInWorldDomainCatalogAndPaperRegistry() {
        var domainMatches = WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id038GameEventPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id038GameEventPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, descriptor.playbackScope());
    }

    @Test
    void forwardsAllWeatherEventsAndDropsAllTenRecordedPlayerEvents() {
        var definition = new Id038GameEventPacketDefinition();

        for (int eventId = 0; eventId < ALL_EVENTS.size(); eventId++) {
            ClientboundGameEventPacket.Type event = ALL_EVENTS.get(eventId);
            float parameter = isWeatherLevelEvent(event) ? 0.5F : eventId + 0.25F;
            if (event == ClientboundGameEventPacket.START_RAINING
                    || event == ClientboundGameEventPacket.STOP_RAINING) {
                parameter = 0.0F;
            }
            PacketRewriteResult result = definition.rewrite(
                    new ClientboundGameEventPacket(event, parameter), descriptor(), rewriter());

            if (WEATHER_EVENTS.contains(event)) {
                ClientboundGameEventPacket forwarded = assertInstanceOf(
                        ClientboundGameEventPacket.class, result.packet().orElseThrow());
                assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
                assertTrue(forwarded.getEvent() == event);
                assertEquals(parameter, forwarded.getParam());
            } else {
                assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
                assertTrue(result.packet().isEmpty());
            }
        }
    }

    @Test
    void preservesEveryValidWeatherLevelBoundaryAndRejectsInvalidLevels() {
        var definition = new Id038GameEventPacketDefinition();

        for (ClientboundGameEventPacket.Type event : WEATHER_LEVEL_EVENTS) {
            for (float level : new float[] {0.0F, 0.25F, 1.0F}) {
                PacketRewriteResult result = definition.rewrite(
                        new ClientboundGameEventPacket(event, level), descriptor(), rewriter());
                ClientboundGameEventPacket forwarded = assertInstanceOf(
                        ClientboundGameEventPacket.class, result.packet().orElseThrow());

                assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
                assertTrue(forwarded.getEvent() == event);
                assertEquals(level, forwarded.getParam());
            }

            for (float invalidLevel : new float[] {
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                -0.01F,
                1.01F
            }) {
                PacketRewriteResult result = definition.rewrite(
                        new ClientboundGameEventPacket(event, invalidLevel), descriptor(), rewriter());
                assertEquals(PacketRewriteResult.Decision.FAIL, result.decision());
                assertTrue(result.packet().isEmpty());
            }
        }
    }

    @Test
    void dropsUnknownEventIdsDecodedByPaperAsNullTypes() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id038GameEventPacketDefinition();

        for (int eventId : new int[] {14, 127, 255}) {
            ByteBuf raw = Unpooled.buffer();
            try {
                raw.writeByte(PACKET_ID);
                raw.writeByte(eventId);
                raw.writeFloat(0.75F);
                ClientboundGameEventPacket decoded = assertInstanceOf(
                        ClientboundGameEventPacket.class, decode(protocol, raw));
                assertNull(decoded.getEvent());

                PacketRewriteResult result = definition.rewrite(decoded, descriptor(), rewriter());
                assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
                assertTrue(result.packet().isEmpty());
            } finally {
                raw.release();
            }
        }
    }

    @Test
    void nativeProtocolRoundTripsAllFourteenEventIdsAndFloatParameters() {
        ProtocolInfo<?> protocol = protocol();

        for (int eventId = 0; eventId < ALL_EVENTS.size(); eventId++) {
            ClientboundGameEventPacket.Type event = ALL_EVENTS.get(eventId);
            float parameter = eventId + 0.125F;
            ByteBuf encoded = encodePacket(
                    protocol, new ClientboundGameEventPacket(event, parameter));
            try {
                ByteBuf wire = encoded.duplicate();
                assertEquals(PACKET_ID, wire.readUnsignedByte());
                assertEquals(eventId, wire.readUnsignedByte());
                assertEquals(parameter, wire.readFloat());
                assertFalse(wire.isReadable());

                ClientboundGameEventPacket decoded = assertInstanceOf(
                        ClientboundGameEventPacket.class, decode(protocol, encoded.duplicate()));
                assertTrue(decoded.getEvent() == event);
                assertEquals(parameter, decoded.getParam());
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedEventAndParameterPrefix() {
        ProtocolInfo<?> protocol = protocol();

        for (int payloadLength = 0; payloadLength < 1 + Float.BYTES; payloadLength++) {
            ByteBuf truncated = Unpooled.buffer();
            try {
                truncated.writeByte(PACKET_ID);
                truncated.writeZero(payloadLength);
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }
    }

    @Test
    void playbackForwardsWeatherAndDropsRecordedPlayerState() {
        var definition = new Id038GameEventPacketDefinition();
        ClientboundGameEventPacket weather = new ClientboundGameEventPacket(
                ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0.75F);
        PacketCodec<Object> weatherCodec = codec(weather);
        PacketRewriteResult weatherResult = definition.handlePlayback(
                frame(), descriptor(), weatherCodec, rewriter());
        ClientboundGameEventPacket rebuilt = assertInstanceOf(
                ClientboundGameEventPacket.class, weatherResult.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, weatherResult.decision());
        assertTrue(rebuilt.getEvent() == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE);
        assertEquals(0.75F, rebuilt.getParam());
        assertEquals(PACKET_ID, definition.encode(rebuilt, descriptor(), weatherCodec).packetId());

        PacketRewriteResult localResult = definition.handlePlayback(
                frame(),
                descriptor(),
                codec(new ClientboundGameEventPacket(
                        ClientboundGameEventPacket.CHANGE_GAME_MODE, 1.0F)),
                rewriter());
        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, localResult.decision());
        assertTrue(localResult.packet().isEmpty());
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketCodec<Object> codec(ClientboundGameEventPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundGameEventPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
                        new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
    }

    private static boolean isWeatherLevelEvent(ClientboundGameEventPacket.Type event) {
        return event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE
                || event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE;
    }
}
