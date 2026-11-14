package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:player_info_update}.
 *
 * <p>Profile data remains available for replay entities while replay players
 * stay out of the observer's tab list. A source chat-session key is signed for
 * its original UUID, so it cannot be carried over to a rewritten profile
 * identity.</p>
 */
final class Id070PlayerInfoUpdatePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundPlayerInfoUpdatePacket,
        Id070PlayerInfoUpdatePacketDefinition.Value> {

    static final int PACKET_ID = 0x46;
    static final String IDENTIFIER = "minecraft:player_info_update";

    Id070PlayerInfoUpdatePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundPlayerInfoUpdatePacket.class);
    }

    @Override
    protected Value read(ClientboundPlayerInfoUpdatePacket packet) {
        return new Value(
                packet.actions(),
                packet.entries().stream().map(Entry::from).toList());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = value.actions();
        boolean hidesPlayerListEntry = actions.contains(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
        // Preserve the profile for rendering, but never list replay players in the observer's tab list.
        return ValueRewriteResult.send(new Value(
                actions,
                value.entries().stream()
                        .map(entry -> entry.rewrite(context, hidesPlayerListEntry))
                        .toList()));
    }

    @Override
    protected ClientboundPlayerInfoUpdatePacket rebuild(Value value) {
        return new ClientboundPlayerInfoUpdatePacket(
                value.actions(),
                value.entries().stream().map(Entry::toPacketEntry).toList());
    }

    /** Immutable semantic packet value with all eight action-controlled fields. */
    record Value(
            EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
            List<Entry> entries) {

        Value {
            Objects.requireNonNull(actions, "actions");
            actions = actions.isEmpty()
                    ? EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class)
                    : EnumSet.copyOf(actions);
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
                    && entries.stream().anyMatch(entry -> entry.profile() == null)) {
                throw new IllegalArgumentException("ADD_PLAYER entries must include a profile");
            }
        }

        @Override
        public EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions() {
            return actions.isEmpty()
                    ? EnumSet.noneOf(ClientboundPlayerInfoUpdatePacket.Action.class)
                    : EnumSet.copyOf(actions);
        }
    }

    /** Snapshot of one player entry, including fields omitted by some action sets. */
    record Entry(
            UUID profileId,
            PlayerProfile profile,
            boolean listed,
            int latency,
            GameType gameMode,
            Component displayName,
            boolean showHat,
            int listOrder,
            ChatSession chatSession) {

        Entry {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(gameMode, "gameMode");
        }

        static Entry from(ClientboundPlayerInfoUpdatePacket.Entry entry) {
            Objects.requireNonNull(entry, "entry");
            return new Entry(
                    entry.profileId(),
                    PlayerProfile.from(entry.profile()),
                    entry.listed(),
                    entry.latency(),
                    entry.gameMode(),
                    entry.displayName(),
                    entry.showHat(),
                    entry.listOrder(),
                    ChatSession.from(entry.chatSession()));
        }

        Entry rewrite(Paper26_2ReplayPacketRewriter context, boolean hidesPlayerListEntry) {
            return new Entry(
                    context.mapUuid(profileId),
                    profile,
                    hidesPlayerListEntry ? false : listed,
                    latency,
                    gameMode,
                    displayName,
                    showHat,
                    listOrder,
                    null);
        }

        ClientboundPlayerInfoUpdatePacket.Entry toPacketEntry() {
            return new ClientboundPlayerInfoUpdatePacket.Entry(
                    profileId,
                    profile == null ? null : profile.toGameProfile(profileId),
                    listed,
                    latency,
                    gameMode,
                    displayName,
                    showHat,
                    listOrder,
                    chatSession == null ? null : chatSession.toPacketValue());
        }
    }

    /** The profile UUID is carried by the entry; only the name and properties live here. */
    record PlayerProfile(String name, ImmutableMultimap<String, Property> properties) {

        PlayerProfile {
            Objects.requireNonNull(name, "name");
            properties = ImmutableMultimap.copyOf(
                    Objects.requireNonNull(properties, "properties"));
        }

        static PlayerProfile from(GameProfile profile) {
            return profile == null
                    ? null
                    : new PlayerProfile(profile.name(), ImmutableMultimap.copyOf(profile.properties()));
        }

        GameProfile toGameProfile(UUID profileId) {
            return new GameProfile(profileId, name, new PropertyMap(properties));
        }
    }

    /** Defensive snapshot of the source-identity-bound signed chat-session data. */
    record ChatSession(UUID sessionId, Instant expiresAt, PublicKey key, byte[] keySignature) {

        ChatSession {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(key, "key");
            keySignature = Objects.requireNonNull(keySignature, "keySignature").clone();
        }

        @Override
        public byte[] keySignature() {
            return keySignature.clone();
        }

        static ChatSession from(RemoteChatSession.Data data) {
            if (data == null) {
                return null;
            }
            var publicKey = data.profilePublicKey();
            return new ChatSession(
                    data.sessionId(),
                    publicKey.expiresAt(),
                    publicKey.key(),
                    publicKey.keySignature());
        }

        RemoteChatSession.Data toPacketValue() {
            return new RemoteChatSession.Data(
                    sessionId,
                    new net.minecraft.world.entity.player.ProfilePublicKey.Data(
                            expiresAt,
                            key,
                            keySignature()));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ChatSession chatSession
                    && sessionId.equals(chatSession.sessionId)
                    && expiresAt.equals(chatSession.expiresAt)
                    && key.equals(chatSession.key)
                    && Arrays.equals(keySignature, chatSession.keySignature);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(sessionId, expiresAt, key)
                    + Arrays.hashCode(keySignature);
        }
    }
}
