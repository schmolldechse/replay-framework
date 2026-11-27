package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.chat;

import java.util.List;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Decides which chat a replay may show.
 *
 * <p>A replay shows what everyone could read: chat messages, death, join and
 * quit messages, advancements. It never shows what the server told one
 * player only, such as command feedback or a whisper. The protocol has no
 * flag for this, so the decision uses what the packet itself carries: the
 * chat type of a chat message and the translation key of a system message.</p>
 *
 * <p>Chat is public unless its type says otherwise, because plugins and
 * Paper itself add chat types. A system message is private unless its key
 * says otherwise, because most of them are command feedback.</p>
 */
final class PublicChat {

    /**
     * Translation keys of system messages the server broadcasts to everyone.
     * A system message with any other content, including plain text written
     * by a plugin, is treated as private.
     */
    private static final List<String> PUBLIC_SYSTEM_KEY_PREFIXES = List.of(
            "death.",
            "multiplayer.player.joined",
            "multiplayer.player.left",
            "chat.type.advancement.");

    private PublicChat() {}

    /** Whether a chat message of this type is visible to everyone. */
    static boolean isPublic(ChatType.Bound chatType) {
        return !(chatType.chatType().is(ChatType.MSG_COMMAND_INCOMING)
                || chatType.chatType().is(ChatType.MSG_COMMAND_OUTGOING)
                || chatType.chatType().is(ChatType.TEAM_MSG_COMMAND_INCOMING)
                || chatType.chatType().is(ChatType.TEAM_MSG_COMMAND_OUTGOING));
    }

    /** Whether a system message is one the server broadcasts to everyone. */
    static boolean isPublicSystemMessage(Component content, boolean overlay) {
        // An overlay is the action bar of one player.
        if (overlay || !(content.getContents() instanceof TranslatableContents translatable)) {
            return false;
        }
        String key = translatable.getKey();
        return PUBLIC_SYSTEM_KEY_PREFIXES.stream().anyMatch(key::startsWith);
    }
}
