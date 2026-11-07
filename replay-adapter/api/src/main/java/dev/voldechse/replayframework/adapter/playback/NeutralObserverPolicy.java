package dev.voldechse.replayframework.adapter.playback;

import java.util.Set;

/**
 * Replay packets that belong to the recorded player's connection, not the
 * observed scene. Live server packets in these families must still reach the
 * observer. NMS-specific payload filtering remains inside each adapter.
 */
public final class NeutralObserverPolicy {
    private NeutralObserverPolicy() {}

    private static final Set<String> RECORDED_CHAT = Set.of(
            "player_chat", "system_chat", "disguised_chat");

    private static final Set<String> LOCAL = Set.of(
            "player_position", "player_rotation", "player_abilities", "set_health",
            "set_experience", "respawn", "container_close", "container_set_content",
            "container_set_data", "container_set_slot", "set_cursor_item", "set_held_slot",
            "set_player_inventory", "move_vehicle", "set_camera", "player_look_at",
            "set_action_bar_text", "open_screen", "mount_screen_open", "player_combat_end",
            "player_combat_enter", "player_combat_kill", "tab_list", "cooldown",
            "commands", "command_suggestions", "custom_chat_completions", "merchant_offers",
            "place_ghost_recipe", "recipe_book_add", "recipe_book_remove", "recipe_book_settings",
            "update_advancements", "select_advancements_tab", "update_recipes", "award_stats",
            "set_title_text", "set_subtitle_text", "set_titles_animation", "clear_titles",
            "open_book", "open_sign_editor", "show_dialog", "clear_dialog", "server_links",
            "server_data", "custom_payload", "delete_chat", "change_difficulty", "game_rule_values", "post_effects",
            "set_objective", "set_display_objective", "set_player_team", "set_score", "reset_score", "waypoint");

    public static boolean isViewerLocal(String identifier) {
        return identifier.startsWith("minecraft:") && LOCAL.contains(identifier.substring(10));
    }

    /** Returns whether a packet presents chat received by the recorded connection. */
    public static boolean isRecordedChatPresentation(String identifier) {
        return identifier.startsWith("minecraft:")
                && RECORDED_CHAT.contains(identifier.substring(10));
    }
}
