package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class PublicChatTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void treatsChatEveryoneReceivesAsPublic() {
        assertTrue(PublicChat.isPublic(bound(registered(ChatType.CHAT))));
        assertTrue(PublicChat.isPublic(bound(registered(ChatType.SAY_COMMAND))));
        assertTrue(PublicChat.isPublic(bound(registered(ChatType.EMOTE_COMMAND))));
    }

    @Test
    void treatsChatTypesOfPluginsAsPublic() {
        ResourceKey<ChatType> pluginType = ResourceKey.create(
                Registries.CHAT_TYPE, net.minecraft.resources.Identifier.parse("paper:raw"));

        assertTrue(PublicChat.isPublic(bound(registered(pluginType))));
        assertTrue(PublicChat.isPublic(bound(Holder.direct(chatType()))));
    }

    @Test
    void treatsWhispersAndTeamMessagesAsPrivate() {
        assertFalse(PublicChat.isPublic(bound(registered(ChatType.MSG_COMMAND_INCOMING))));
        assertFalse(PublicChat.isPublic(bound(registered(ChatType.MSG_COMMAND_OUTGOING))));
        assertFalse(PublicChat.isPublic(bound(registered(ChatType.TEAM_MSG_COMMAND_INCOMING))));
        assertFalse(PublicChat.isPublic(bound(registered(ChatType.TEAM_MSG_COMMAND_OUTGOING))));
    }

    @Test
    void treatsServerBroadcastsAsPublicSystemMessages() {
        for (String key : List.of(
                "death.attack.fall",
                "death.fell.accident.generic",
                "multiplayer.player.joined",
                "multiplayer.player.joined.renamed",
                "multiplayer.player.left",
                "chat.type.advancement.task",
                "chat.type.advancement.challenge")) {
            assertTrue(
                    PublicChat.isPublicSystemMessage(Component.translatable(key, "Alex"), false),
                    key);
        }
    }

    @Test
    void treatsMessagesToOnePlayerAsPrivateSystemMessages() {
        assertFalse(PublicChat.isPublicSystemMessage(
                Component.translatable("commands.gamemode.success.self", "Creative"), false));
        assertFalse(PublicChat.isPublicSystemMessage(
                Component.translatable("gameMode.changed", "Creative"), false));
        assertFalse(PublicChat.isPublicSystemMessage(
                Component.literal("Recording started"), false));
        assertFalse(PublicChat.isPublicSystemMessage(Component.empty(), false));
    }

    @Test
    void treatsTheActionBarAsPrivate() {
        assertFalse(PublicChat.isPublicSystemMessage(
                Component.translatable("death.attack.fall", "Alex"), true));
    }

    private static ChatType.Bound bound(Holder<ChatType> type) {
        return new ChatType.Bound(type, Component.literal("Alex"), Optional.empty());
    }

    private static Holder<ChatType> registered(ResourceKey<ChatType> key) {
        MappedRegistry<ChatType> chatTypes = new MappedRegistry<>(
                Registries.CHAT_TYPE, Lifecycle.stable(), false);
        Holder.Reference<ChatType> holder = chatTypes.register(
                key, chatType(), RegistrationInfo.BUILT_IN);
        chatTypes.freeze();
        return holder;
    }

    private static ChatType chatType() {
        ChatTypeDecoration decoration = new ChatTypeDecoration(
                "chat.type.text",
                List.of(ChatTypeDecoration.Parameter.SENDER, ChatTypeDecoration.Parameter.CONTENT),
                Style.EMPTY);
        return new ChatType(decoration, decoration);
    }
}
