package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.HashMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id070PlayerInfoUpdatePacketDefinitionTest {

    private static final int PACKET_ID = 0x46;
    private static final String TYPE_NAME = "play.clientbound.minecraft:player_info_update";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RECORDED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SECOND_RECORDED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID THIRD_RECORDED_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ZERO_UUID = new UUID(0L, 0L);
    private static final UUID MAX_UUID = new UUID(-1L, -1L);
    private static final Property TEXTURE_PROPERTY =
            new Property("textures", "encoded-textures", "signed-textures");
    private static final byte[] CHAT_KEY_SIGNATURE = {1, 3, 5, 7, 9};

    private static KeyPair CHAT_KEY_PAIR;

    @BeforeAll
    static void bootstrapMinecraftRegistries() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(1024);
        CHAT_KEY_PAIR = keyPairGenerator.generateKeyPair();
    }

    @Test
    void classifiesAndRegistersPlayerInfoAsIdentityBearingCheckpointState() {
        var definition = new Id070PlayerInfoUpdatePacketDefinition();

        assertEquals(70, definition.packetId());
        assertEquals(PACKET_ID, Id070PlayerInfoUpdatePacketDefinition.PACKET_ID);
        assertEquals("minecraft:player_info_update", Id070PlayerInfoUpdatePacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
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
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void readsRewritesAndRebuildsAllEntryFieldsWithoutLeakingSourceIdentities() {
        var definition = new Id070PlayerInfoUpdatePacketDefinition();
        var rewriter = rewriter();
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions =
                EnumSet.allOf(ClientboundPlayerInfoUpdatePacket.Action.class);
        List<ClientboundPlayerInfoUpdatePacket.Entry> sourceEntries = List.of(
                entry(RECORDED_ID, profile(RECORDED_ID), true, -1, GameType.CREATIVE,
                        Component.literal("Recorded name"), true, -4, chatSession()),
                entry(SECOND_RECORDED_ID, profile(SECOND_RECORDED_ID), false, 275,
                        GameType.SPECTATOR, null, false, 12, null),
                entry(RECORDED_ID, profile(RECORDED_ID), true, 3, GameType.SURVIVAL,
                        Component.literal("Duplicate profile"), true, 0, chatSession()));
        ClientboundPlayerInfoUpdatePacket source = packet(actions, sourceEntries);

        var value = definition.read(source);
        assertEquals(actions, value.actions());
        assertEquals(RECORDED_ID, value.entries().getFirst().profileId());
        assertEquals("Recorded name", value.entries().getFirst().displayName().getString());
        assertEquals(CHAT_KEY_SIGNATURE.length,
                value.entries().getFirst().chatSession().keySignature().length);

        var rewritten = definition.rewrite(value, rewriter).value().orElseThrow();
        assertEquals(actions, rewritten.actions());
        assertEquals(3, rewritten.entries().size());
        assertEquals(List.of(
                        rewriter.mapUuid(RECORDED_ID),
                        rewriter.mapUuid(SECOND_RECORDED_ID),
                        rewriter.mapUuid(RECORDED_ID)),
                rewritten.entries().stream()
                        .map(Id070PlayerInfoUpdatePacketDefinition.Entry::profileId)
                        .toList());
        assertEquals(rewritten.entries().getFirst().profileId(),
                rewritten.entries().get(2).profileId());

        for (Id070PlayerInfoUpdatePacketDefinition.Entry entry : rewritten.entries()) {
            assertFalse(entry.listed(), "replay profiles must stay out of the observer's tab list");
            assertNull(entry.chatSession(), "the source key is signed for the unmapped profile UUID");
            assertNotEquals(OBSERVER_ID, entry.profileId());
            assertNotEquals(SESSION_ID, entry.profileId());
            assertNotEquals(RECORDED_ID, entry.profileId());
            assertNotEquals(SECOND_RECORDED_ID, entry.profileId());
        }

        var rewrittenFirst = rewritten.entries().getFirst();
        assertEquals("Recorded", rewrittenFirst.profile().name());
        assertEquals(ImmutableMultimap.of("textures", TEXTURE_PROPERTY),
                rewrittenFirst.profile().properties());
        assertEquals(-1, rewrittenFirst.latency());
        assertEquals(GameType.CREATIVE, rewrittenFirst.gameMode());
        assertEquals("Recorded name", rewrittenFirst.displayName().getString());
        assertTrue(rewrittenFirst.showHat());
        assertEquals(-4, rewrittenFirst.listOrder());

        ClientboundPlayerInfoUpdatePacket rebuilt = definition.rebuild(rewritten);
        assertEquals(actions, rebuilt.actions());
        assertEquals(rewritten.entries().size(), rebuilt.entries().size());
        assertEquals(rewrittenFirst.profileId(), rebuilt.entries().getFirst().profileId());
        assertEquals(rewrittenFirst.profileId(), rebuilt.entries().getFirst().profile().id());
        assertEquals("Recorded", rebuilt.entries().getFirst().profile().name());
        assertEquals(ImmutableMultimap.of("textures", TEXTURE_PROPERTY),
                ImmutableMultimap.copyOf(rebuilt.entries().getFirst().profile().properties()));
        assertFalse(rebuilt.entries().getFirst().listed());
        assertNull(rebuilt.entries().getFirst().chatSession());
    }

    @Test
    void preservesListedValueWhenThatActionIsAbsent() {
        var definition = new Id070PlayerInfoUpdatePacketDefinition();
        var entry = Id070PlayerInfoUpdatePacketDefinition.Entry.from(
                entry(RECORDED_ID, null, true, 42, GameType.SURVIVAL,
                        null, false, 0, null));
        var rewritten = definition.rewrite(
                new Id070PlayerInfoUpdatePacketDefinition.Value(
                        EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY),
                        List.of(entry)),
                rewriter()).value().orElseThrow();

        assertTrue(rewritten.entries().getFirst().listed());
        assertEquals(42, rewritten.entries().getFirst().latency());
    }

    @Test
    void mapsEveryActionCombinationThroughTheNativePaperCodec() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerInfoUpdatePacket.Action[] allActions =
                ClientboundPlayerInfoUpdatePacket.Action.values();
        assertEquals(8, allActions.length);

        for (int mask = 0; mask < (1 << allActions.length); mask++) {
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions =
                    EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class);
            for (int actionIndex = 0; actionIndex < allActions.length; actionIndex++) {
                if ((mask & (1 << actionIndex)) != 0) {
                    actions.add(allActions[actionIndex]);
                }
            }
            GameProfile playerProfile = actions.contains(
                    ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                    ? profile(RECORDED_ID)
                    : null;
            RemoteChatSession.Data session = actions.contains(
                    ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT)
                    ? chatSession()
                    : null;
            ClientboundPlayerInfoUpdatePacket source = packet(actions, List.of(entry(
                    RECORDED_ID,
                    playerProfile,
                    true,
                    -1,
                    GameType.CREATIVE,
                    Component.literal("Codec combination " + mask),
                    true,
                    17,
                    session)));

            ByteBuf encoded = encodePacket(protocol, source);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf idView = encoded.duplicate();
                assertEquals(PACKET_ID, VarInt.read(idView), "action mask " + mask);
                ClientboundPlayerInfoUpdatePacket decoded = assertInstanceOf(
                        ClientboundPlayerInfoUpdatePacket.class, decode(protocol, encoded));
                assertEquals(actions, decoded.actions(), "action mask " + mask);
                assertEquals(1, decoded.entries().size(), "action mask " + mask);
                assertEquals(RECORDED_ID, decoded.entries().getFirst().profileId());
                assertFalse(encoded.isReadable(), "action mask " + mask);
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
                    assertEquals("Recorded", decoded.entries().getFirst().profile().name());
                } else {
                    assertNull(decoded.entries().getFirst().profile());
                }
                if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT)) {
                    assertEquals(session, decoded.entries().getFirst().chatSession());
                } else {
                    assertNull(decoded.entries().getFirst().chatSession());
                }

                ByteBuf reencoded = encodePacket(protocol, decoded);
                try {
                    assertArrayEquals(expectedWire, bytes(reencoded), "action mask " + mask);
                } finally {
                    reencoded.release();
                }
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void keepsEmptyListsDuplicatesAndWireOrder() {
        ProtocolInfo<?> protocol = protocol();
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY);
        List<List<UUID>> profileIds = List.of(
                List.of(),
                List.of(ZERO_UUID),
                List.of(MAX_UUID, RECORDED_ID, MAX_UUID),
                ids(127),
                ids(128));

        for (List<UUID> expectedIds : profileIds) {
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries = expectedIds.stream()
                    .map(id -> entry(id, null, false, 15, GameType.SURVIVAL, null, false, 0, null))
                    .toList();
            ClientboundPlayerInfoUpdatePacket source = packet(actions, entries);
            ByteBuf encoded = encodePacket(protocol, source);
            try {
                byte[] expectedWire = bytes(encoded);
                ByteBuf inspect = encoded.duplicate();
                assertEquals(PACKET_ID, VarInt.read(inspect));
                assertEquals(0x10, inspect.readUnsignedByte());
                assertEquals(expectedIds.size(), VarInt.read(inspect));
                for (UUID expectedId : expectedIds) {
                    assertEquals(expectedId, FriendlyByteBuf.readUUID(inspect));
                    VarInt.read(inspect); // UPDATE_LATENCY value for each entry
                }
                assertFalse(inspect.isReadable());

                ClientboundPlayerInfoUpdatePacket decoded = assertInstanceOf(
                        ClientboundPlayerInfoUpdatePacket.class, decode(protocol, encoded));
                assertEquals(expectedIds, decoded.entries().stream()
                        .map(ClientboundPlayerInfoUpdatePacket.Entry::profileId)
                        .toList());
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

    @Test
    void valueDefensivelyCopiesActionsEntriesProfilesAndChatSignatureBytes() {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> mutableActions = EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
        List<Id070PlayerInfoUpdatePacketDefinition.Entry> mutableEntries = new ArrayList<>(List.of(
                new Id070PlayerInfoUpdatePacketDefinition.Entry(
                        RECORDED_ID,
                        Id070PlayerInfoUpdatePacketDefinition.PlayerProfile.from(profile(RECORDED_ID)),
                        true,
                        1,
                        GameType.SURVIVAL,
                        null,
                        false,
                        0,
                        Id070PlayerInfoUpdatePacketDefinition.ChatSession.from(chatSession()))));
        var value = new Id070PlayerInfoUpdatePacketDefinition.Value(mutableActions, mutableEntries);
        mutableActions.clear();
        mutableEntries.clear();

        assertEquals(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), value.actions());
        assertEquals(1, value.entries().size());
        assertThrows(UnsupportedOperationException.class,
                () -> value.entries().clear());
        value.actions().clear();
        assertEquals(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), value.actions());

        byte[] exposedSignature = value.entries().getFirst().chatSession().keySignature();
        exposedSignature[0] = 99;
        assertEquals(1, value.entries().getFirst().chatSession().keySignature()[0]);
        assertThrows(UnsupportedOperationException.class,
                () -> value.entries().getFirst().profile().properties().put(
                        "textures", new Property("textures", "changed", null)));

        byte[] sourceSignature = CHAT_KEY_SIGNATURE.clone();
        var sourceChatData = new RemoteChatSession.Data(
                SESSION_ID,
                new ProfilePublicKey.Data(
                        Instant.parse("2030-01-01T00:00:00Z"),
                        CHAT_KEY_PAIR.getPublic(),
                        sourceSignature));
        var detachedChatSession = Id070PlayerInfoUpdatePacketDefinition.ChatSession.from(sourceChatData);
        sourceSignature[0] = 99;
        assertEquals(1, detachedChatSession.keySignature()[0]);

        var sourceProperties = HashMultimap.<String, Property>create();
        sourceProperties.put("textures", TEXTURE_PROPERTY);
        GameProfile sourceProfile = new GameProfile(
                RECORDED_ID, "Recorded", new PropertyMap(sourceProperties));
        var definition = new Id070PlayerInfoUpdatePacketDefinition();
        var capturedProfile = definition.read(packet(
                        EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                        List.of(entry(RECORDED_ID, sourceProfile, false, 0,
                                GameType.SURVIVAL, null, false, 0, null))))
                .entries().getFirst().profile();
        sourceProperties.put("textures", new Property("textures", "changed", null));
        assertEquals(ImmutableMultimap.of("textures", TEXTURE_PROPERTY), capturedProfile.properties());
    }

    @Test
    void rejectsNullSemanticFieldsAndAcceptsEmptyActionSet() {
        assertThrows(NullPointerException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.Value(null, List.of()));
        assertThrows(NullPointerException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.Value(
                        EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class), null));
        assertThrows(NullPointerException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.Value(
                        EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class),
                        Arrays.asList((Id070PlayerInfoUpdatePacketDefinition.Entry) null)));
        assertThrows(NullPointerException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.Entry(
                        null, null, false, 0, GameType.DEFAULT_MODE, null, false, 0, null));
        assertThrows(NullPointerException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.Entry(
                        RECORDED_ID, null, false, 0, null, null, false, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.Value(
                        EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER),
                        List.of(new Id070PlayerInfoUpdatePacketDefinition.Entry(
                                RECORDED_ID, null, false, 0, GameType.SURVIVAL, null, false, 0, null))));

        var empty = new Id070PlayerInfoUpdatePacketDefinition.Value(
                EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class), List.of());
        assertTrue(empty.actions().isEmpty());
        assertTrue(empty.entries().isEmpty());
        assertThrows(NullPointerException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.ChatSession(
                        null, Instant.EPOCH, CHAT_KEY_PAIR.getPublic(), new byte[0]));
        assertThrows(NullPointerException.class,
                () -> new Id070PlayerInfoUpdatePacketDefinition.ChatSession(
                        SESSION_ID, Instant.EPOCH, CHAT_KEY_PAIR.getPublic(), null));
    }

    @Test
    void retriesUuidMappingWhenTheFirstReplayCandidateIsReserved() {
        UUID firstCandidate = UUID.nameUUIDFromBytes(
                (SESSION_ID + ":uuid:" + RECORDED_ID).getBytes(StandardCharsets.UTF_8));
        var rewriter = rewriter(Set.of(
                OBSERVER_ID,
                SESSION_ID,
                RECORDED_ID,
                firstCandidate));

        UUID mapped = rewriter.mapUuid(RECORDED_ID);

        assertNotEquals(firstCandidate, mapped);
        assertNotEquals(OBSERVER_ID, mapped);
        assertNotEquals(RECORDED_ID, mapped);
        assertEquals(mapped, rewriter.mapUuid(RECORDED_ID));
    }

    @Test
    void handlesPlaybackAndRebuildsWithTheNativeCodec() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id070PlayerInfoUpdatePacketDefinition();
        var rewriter = rewriter();
        ClientboundPlayerInfoUpdatePacket source = packet(
                EnumSet.allOf(ClientboundPlayerInfoUpdatePacket.Action.class),
                List.of(entry(RECORDED_ID, profile(RECORDED_ID), true, 75,
                        GameType.ADVENTURE, Component.literal("Replay profile"), true, 9,
                        chatSession())));
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = nativeCodec(protocol, decodes, encodes);

        PacketRewriteResult result = definition.handlePlayback(
                frame(nativePayload(protocol, source)), descriptor(), codec, rewriter);
        ClientboundPlayerInfoUpdatePacket rewritten = assertInstanceOf(
                ClientboundPlayerInfoUpdatePacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals("minecraft:player_info_update", rewritten.type().id().toString());
        assertEquals(EnumSet.allOf(ClientboundPlayerInfoUpdatePacket.Action.class), rewritten.actions());
        assertEquals(rewriter.mapUuid(RECORDED_ID), rewritten.entries().getFirst().profileId());
        assertEquals(rewriter.mapUuid(RECORDED_ID), rewritten.entries().getFirst().profile().id());
        assertFalse(rewritten.entries().getFirst().listed());
        assertNull(rewritten.entries().getFirst().chatSession());
        assertEquals(1, decodes.get());
        assertEquals(0, encodes.get());

        var encoded = definition.encode(rewritten, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        assertArrayEquals(nativePayload(protocol, rewritten), encoded.payload());
        assertEquals(1, encodes.get());
    }

    @Test
    void rejectsDescriptorAndDecodedTypeMismatches() {
        var definition = new Id070PlayerInfoUpdatePacketDefinition();
        PacketCodec<Object> codec = nativeCodec(
                protocol(), new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.rewrite(new Object(), mismatch, rewriter()));
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedBodyPrefixAndInvalidEntryCounts() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerInfoUpdatePacket source = packet(
                EnumSet.allOf(ClientboundPlayerInfoUpdatePacket.Action.class),
                List.of(entry(RECORDED_ID, profile(RECORDED_ID), true, 15,
                        GameType.SURVIVAL, Component.literal("Truncation"), true, 8,
                        chatSession())));
        byte[] body = nativePayload(protocol, source);

        for (int length = 0; length < body.length; length++) {
            ByteBuf truncated = Unpooled.buffer();
            try {
                VarInt.write(truncated, PACKET_ID);
                truncated.writeBytes(body, 0, length);
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }

        assertNativeDecodeFails(protocol, input -> {
            input.writeByte(0);
            VarInt.write(input, -1);
        });
        assertNativeDecodeFails(protocol, input -> {
            input.writeByte(0);
            VarInt.write(input, 2);
        });
        assertNativeDecodeFails(protocol, input -> input.writeByte(0x80));
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundPlayerInfoUpdatePacket source = packet(
                EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class), List.of());
        ByteBuf encoded = encodePacket(protocol, source);
        try {
            encoded.writeByte(0x7A);
            ClientboundPlayerInfoUpdatePacket decoded = assertInstanceOf(
                    ClientboundPlayerInfoUpdatePacket.class, decode(protocol, encoded));
            assertTrue(decoded.actions().isEmpty());
            assertTrue(decoded.entries().isEmpty());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static List<UUID> ids(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> new UUID(0x1020304050607080L, index))
                .toList();
    }

    private static GameProfile profile(UUID id) {
        return new GameProfile(
                id,
                "Recorded",
                new PropertyMap(ImmutableMultimap.of("textures", TEXTURE_PROPERTY)));
    }

    private static RemoteChatSession.Data chatSession() {
        return new RemoteChatSession.Data(
                SESSION_ID,
                new ProfilePublicKey.Data(
                        Instant.parse("2030-01-01T00:00:00Z"),
                        CHAT_KEY_PAIR.getPublic(),
                        CHAT_KEY_SIGNATURE));
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry entry(
            UUID profileId,
            GameProfile profile,
            boolean listed,
            int latency,
            GameType gameMode,
            Component displayName,
            boolean showHat,
            int listOrder,
            RemoteChatSession.Data chatSession) {
        return new ClientboundPlayerInfoUpdatePacket.Entry(
                profileId,
                profile,
                listed,
                latency,
                gameMode,
                displayName,
                showHat,
                listOrder,
                chatSession);
    }

    private static ClientboundPlayerInfoUpdatePacket packet(
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        return new ClientboundPlayerInfoUpdatePacket(actions, entries);
    }

    private static void assertNativeDecodeFails(
            ProtocolInfo<?> protocol,
            java.util.function.Consumer<ByteBuf> bodyWriter) {
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
            bodyWriter.accept(input);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
        }
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundPlayerInfoUpdatePacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
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
        return rewriter(Set.of(
                OBSERVER_ID,
                SESSION_ID,
                RECORDED_ID,
                SECOND_RECORDED_ID,
                THIRD_RECORDED_ID));
    }

    private static Paper26_2ReplayPacketRewriter rewriter(Set<UUID> reservedUuids) {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID), reservedUuids, Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
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
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
                replayable,
                direction == PacketDescriptor.Direction.CLIENTBOUND && replayable,
                "paper-26.2/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> nativeCodec(
            ProtocolInfo<?> protocol,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                if (!(packet instanceof Packet<?> nativePacket)) {
                    throw new AssertionError("native packet codec received a non-packet value");
                }
                ByteBuf wire = encodePacket(protocol, nativePacket);
                try {
                    int packetId = VarInt.read(wire);
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            packetId,
                            bytes(wire));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                ByteBuf wire = Unpooled.buffer();
                try {
                    VarInt.write(wire, frame.packetId());
                    wire.writeBytes(frame.payload());
                    return Id070PlayerInfoUpdatePacketDefinitionTest.decode(protocol, wire);
                } finally {
                    wire.release();
                }
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
