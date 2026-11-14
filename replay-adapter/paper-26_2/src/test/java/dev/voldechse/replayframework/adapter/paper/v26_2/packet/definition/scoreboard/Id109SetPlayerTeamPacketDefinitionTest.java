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
import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.TeamColor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id109SetPlayerTeamPacketDefinitionTest {

    private static final int PACKET_ID = 109;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_player_team";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000191");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000192");
    private static GlobalConfiguration originalGlobalConfiguration;
    private static GlobalConfiguration.Collisions originalCollisions;
    private static boolean originalPlayerCollisions;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        installTestGlobalConfiguration();
    }

    @AfterAll
    static void restoreTestGlobalConfiguration() {
        if (originalGlobalConfiguration == null) {
            setGlobalConfiguration(null);
        } else {
            originalGlobalConfiguration.collisions = originalCollisions;
            if (originalCollisions != null) {
                originalCollisions.enablePlayerCollisions = originalPlayerCollisions;
            }
        }
    }

    @Test
    void classifiesTeamMutationsAsCapturedViewerLocalState() {
        var definition = new Id109SetPlayerTeamPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x6D, Id109SetPlayerTeamPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_player_team", Id109SetPlayerTeamPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = ScoreboardPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id109SetPlayerTeamPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id109SetPlayerTeamPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeCodecRoundTripsAllFiveMethodsAndConditionalFields() {
        ProtocolInfo<?> protocol = protocol();
        PlayerTeam team = team("arena_red");
        team.getPlayers().addAll(List.of("Alice", "Zoë", "玩家_3"));
        List<ClientboundSetPlayerTeamPacket> packets = List.of(
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true),
                ClientboundSetPlayerTeamPacket.createRemovePacket(team),
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false),
                ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team, "joiner-玩家", ClientboundSetPlayerTeamPacket.Action.ADD),
                ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team, "leaver-élève", ClientboundSetPlayerTeamPacket.Action.REMOVE));
        List<Integer> methods = List.of(0, 1, 2, 3, 4);

        for (int index = 0; index < packets.size(); index++) {
            ClientboundSetPlayerTeamPacket packet = packets.get(index);
            assertNativeRoundTrip(protocol, packet);
            assertWireMethodAndShape(protocol, packet, methods.get(index));
        }

        assertEquals(ClientboundSetPlayerTeamPacket.Action.ADD, packets.get(0).getTeamAction());
        assertEquals(ClientboundSetPlayerTeamPacket.Action.ADD, packets.get(0).getPlayerAction());
        assertEquals(ClientboundSetPlayerTeamPacket.Action.REMOVE, packets.get(1).getTeamAction());
        assertEquals(Optional.empty(), packets.get(1).getParameters());
        assertTrue(packets.get(1).getPlayers().isEmpty());
        assertNull(packets.get(2).getTeamAction());
        assertNull(packets.get(2).getPlayerAction());
        assertNull(packets.get(3).getTeamAction());
        assertEquals(ClientboundSetPlayerTeamPacket.Action.ADD, packets.get(3).getPlayerAction());
        assertEquals(List.of("joiner-玩家"), List.copyOf(packets.get(3).getPlayers()));
        assertNull(packets.get(4).getTeamAction());
        assertEquals(ClientboundSetPlayerTeamPacket.Action.REMOVE, packets.get(4).getPlayerAction());
        assertEquals(List.of("leaver-élève"), List.copyOf(packets.get(4).getPlayers()));
    }

    @Test
    void nativeCodecPreservesEveryVisibilityCollisionColorAndOptionsCombination() {
        ProtocolInfo<?> protocol = protocol();
        PlayerTeam team = team("all_parameters");
        List<Optional<TeamColor>> colors = new ArrayList<>();
        colors.add(Optional.empty());
        for (TeamColor color : TeamColor.values()) {
            colors.add(Optional.of(color));
        }

        for (Team.Visibility visibility : Team.Visibility.values()) {
            for (Team.CollisionRule collisionRule : Team.CollisionRule.values()) {
                for (Optional<TeamColor> color : colors) {
                    for (int options = 0; options <= 3; options++) {
                        team.setNameTagVisibility(visibility);
                        team.setCollisionRule(collisionRule);
                        team.setColor(color);
                        team.setAllowFriendlyFire((options & 1) != 0);
                        team.setSeeFriendlyInvisibles((options & 2) != 0);

                        ClientboundSetPlayerTeamPacket packet =
                                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true);
                        assertEquals((byte) options, packet.getParameters().orElseThrow().options());
                        assertNativeRoundTrip(protocol, packet);
                    }
                }
            }
        }
    }

    @Test
    void nativeCodecPreservesStyledAndNestedTeamComponents() {
        ProtocolInfo<?> protocol = protocol();
        PlayerTeam team = team("styled_team");
        Component displayName = Component.translatable(
                "team.display", Component.literal("隊名").withStyle(style(0xAA00AA)));
        team.setDisplayName(displayName);
        team.setPlayerPrefix(Component.literal("【前】").withStyle(style(0x55FF55)));
        team.setPlayerSuffix(Component.literal("【後】").withStyle(style(0xFFAA00)));

        ClientboundSetPlayerTeamPacket packet =
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false);
        assertNativeRoundTrip(protocol, packet);

        var parameters = packet.getParameters().orElseThrow();
        assertEquals(displayName, parameters.displayName());
        assertEquals("【前】", parameters.playerPrefix().getString());
        assertEquals("【後】", parameters.playerSuffix().getString());
    }

    @Test
    void addMethodSupportsEmptyAndPopulatedPlayerListsWhileChangeOmitsTheList() {
        ProtocolInfo<?> protocol = protocol();
        PlayerTeam emptyTeam = team("empty_team");
        PlayerTeam populatedTeam = team("populated_team");
        populatedTeam.getPlayers().addAll(List.of("one", "two", "三"));

        ClientboundSetPlayerTeamPacket emptyAdd =
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(emptyTeam, true);
        ClientboundSetPlayerTeamPacket populatedAdd =
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(populatedTeam, true);
        ClientboundSetPlayerTeamPacket change =
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(populatedTeam, false);

        assertNativeRoundTrip(protocol, emptyAdd);
        assertNativeRoundTrip(protocol, populatedAdd);
        assertNativeRoundTrip(protocol, change);
        assertTrue(emptyAdd.getPlayers().isEmpty());
        assertEquals(Set.of("one", "two", "三"), Set.copyOf(populatedAdd.getPlayers()));
        assertEquals(3, populatedAdd.getPlayers().size());
        assertTrue(change.getPlayers().isEmpty());
        assertTrue(change.getParameters().isPresent());
    }

    @Test
    void nativeDecoderUsesEmptyNameOnlyShapeForUnknownMethods() {
        ProtocolInfo<?> protocol = protocol();

        for (byte method : new byte[] {5, 0x7F, (byte) 0x80, (byte) 0xFF}) {
            ByteBuf wire = Unpooled.buffer();
            try {
                VarInt.write(wire, PACKET_ID);
                FriendlyByteBuf fields = new FriendlyByteBuf(wire);
                fields.writeUtf("future_team").writeByte(method).writeByte(0x7A);

                ClientboundSetPlayerTeamPacket decoded = assertInstanceOf(
                        ClientboundSetPlayerTeamPacket.class, decode(protocol, wire));
                assertEquals("future_team", decoded.getName());
                assertEquals(Optional.empty(), decoded.getParameters());
                assertTrue(decoded.getPlayers().isEmpty());
                assertNull(decoded.getTeamAction());
                assertNull(decoded.getPlayerAction());
                assertEquals(0x7A, wire.readUnsignedByte());
                assertFalse(wire.isReadable());

                assertArrayEquals(
                        body(protocol, decoded),
                        unknownMethodBody("future_team", method));
            } finally {
                wire.release();
            }
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedDefinedMethodPayload() {
        ProtocolInfo<?> protocol = protocol();
        PlayerTeam team = team("truncation");
        team.getPlayers().addAll(List.of("first", "second"));
        List<ClientboundSetPlayerTeamPacket> packets = List.of(
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true),
                ClientboundSetPlayerTeamPacket.createRemovePacket(team),
                ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false),
                ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team, "member", ClientboundSetPlayerTeamPacket.Action.ADD),
                ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team, "member", ClientboundSetPlayerTeamPacket.Action.REMOVE));

        for (int method = 0; method < packets.size(); method++) {
            byte[] completeBody = body(protocol, packets.get(method));
            for (int length = 0; length < completeBody.length; length++) {
                assertNativeDecodeFails(
                        protocol,
                        java.util.Arrays.copyOf(completeBody, length),
                        "truncated method " + method + " body at " + length
                                + " of " + completeBody.length + " bytes");
            }
        }

        assertNativeDecodeFails(protocol, new byte[] {(byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {0, 0});
        assertNativeDecodeFails(
                protocol,
                new byte[] {0, 0, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                    (byte) 0x80, (byte) 0x80});
    }

    @Test
    void nativeDecoderLeavesSubsequentPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(
                protocol,
                ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team("join_team"), "joined", ClientboundSetPlayerTeamPacket.Action.ADD));
        try {
            wire.writeByte(0x7A);
            ClientboundSetPlayerTeamPacket decoded = assertInstanceOf(
                    ClientboundSetPlayerTeamPacket.class, decode(protocol, wire));

            assertEquals("join_team", decoded.getName());
            assertEquals(List.of("joined"), List.copyOf(decoded.getPlayers()));
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void playbackDropsAllTeamMethodsAndMalformedPayloadsBeforeCodecAccess() {
        ProtocolInfo<?> protocol = protocol();
        PlayerTeam team = team("drop_team");
        team.getPlayers().add("member");
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(body(protocol, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true)));
        payloads.add(body(protocol, ClientboundSetPlayerTeamPacket.createRemovePacket(team)));
        payloads.add(body(protocol, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false)));
        payloads.add(body(protocol, ClientboundSetPlayerTeamPacket.createPlayerPacket(
                team, "member", ClientboundSetPlayerTeamPacket.Action.ADD)));
        payloads.add(body(protocol, ClientboundSetPlayerTeamPacket.createPlayerPacket(
                team, "member", ClientboundSetPlayerTeamPacket.Action.REMOVE)));
        payloads.add(new byte[0]);
        payloads.add(new byte[] {(byte) 0x80});
        payloads.add(new byte[] {1, 0, (byte) 0x80});
        payloads.add(new byte[] {1, 3, (byte) 0x80});

        var definition = new Id109SetPlayerTeamPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        for (byte[] payload : payloads) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingTheDecodedPacket() {
        PacketRewriteResult result = new Id109SetPlayerTeamPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id109SetPlayerTeamPacketDefinition();
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
            ProtocolInfo<?> protocol, ClientboundSetPlayerTeamPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(expected.getName(), fields.readUtf());
            assertEquals(GamePacketTypes.CLIENTBOUND_SET_PLAYER_TEAM, expected.type());

            ClientboundSetPlayerTeamPacket decoded = assertInstanceOf(
                    ClientboundSetPlayerTeamPacket.class, decode(protocol, encoded));
            assertEquals(expected.getName(), decoded.getName());
            assertEquals(List.copyOf(expected.getPlayers()), List.copyOf(decoded.getPlayers()));
            assertEquals(expected.getParameters(), decoded.getParameters());
            assertEquals(expected.getTeamAction(), decoded.getTeamAction());
            assertEquals(expected.getPlayerAction(), decoded.getPlayerAction());
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

    private static void assertWireMethodAndShape(
            ProtocolInfo<?> protocol, ClientboundSetPlayerTeamPacket packet, int expectedMethod) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(packet.getName(), fields.readUtf());
            assertEquals(expectedMethod, fields.readByte());

            if (expectedMethod == 1) {
                assertFalse(fields.isReadable(), "remove-team carries no optional fields");
            } else if (expectedMethod == 3 || expectedMethod == 4) {
                assertEquals(
                        List.copyOf(packet.getPlayers()),
                        fields.readList(FriendlyByteBuf::readUtf));
                assertFalse(fields.isReadable(), "member methods carry only their player list");
            } else {
                assertTrue(fields.isReadable(), "create/update carry team parameters");
            }
        } finally {
            encoded.release();
        }
    }

    private static PlayerTeam team(String name) {
        PlayerTeam team = new Scoreboard().addPlayerTeam(name);
        team.setDisplayName(Component.literal("Team: " + name));
        team.setPlayerPrefix(Component.literal("["));
        team.setPlayerSuffix(Component.literal("]"));
        return team;
    }

    private static void installTestGlobalConfiguration() {
        originalGlobalConfiguration = GlobalConfiguration.get();
        GlobalConfiguration configuration = originalGlobalConfiguration;
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
            setGlobalConfiguration(configuration);
        }
        originalCollisions = configuration.collisions;
        if (originalCollisions != null) {
            originalPlayerCollisions = originalCollisions.enablePlayerCollisions;
        }
        if (configuration.collisions == null) {
            configuration.collisions = configuration.new Collisions();
        }
        configuration.collisions.enablePlayerCollisions = true;
    }

    private static void setGlobalConfiguration(GlobalConfiguration configuration) {
        try {
            Method setter = GlobalConfiguration.class.getDeclaredMethod(
                    "set", GlobalConfiguration.class);
            setter.setAccessible(true);
            setter.invoke(null, configuration);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not update Paper test configuration", failure);
        }
    }

    private static Style style(int color) {
        return Style.EMPTY.withColor(color).withBold(true);
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

    private static byte[] unknownMethodBody(String teamName, byte method) {
        ByteBuf body = Unpooled.buffer();
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(body);
            fields.writeUtf(teamName).writeByte(method);
            return bytes(body);
        } finally {
            body.release();
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

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        assertNativeDecodeFails(protocol, body, "malformed packet body");
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
                throw new AssertionError("viewer-local team mutation must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local team mutation must not be decoded");
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
