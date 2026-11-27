package dev.voldechse.replayframework.adapter.paper.v26_3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Checks the adapter's implemented packet manifest and fallback policy as a whole. */
class PacketCoverageTest {

    private static final List<ExpectedPacket> IMPLEMENTED_PACKETS = List.of(
            control(0x00, "bundle_delimiter"),
            stateful(0x01, "add_entity", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x02, "animate", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x03, "award_stats", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x04, "block_changed_ack"),
            ephemeral(0x05, "block_destruction", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x06, "block_entity_data", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x07, "block_event", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x08, "block_update", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x09, "boss_event", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x0A, "change_difficulty", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x0B, "chunk_batch_finished"),
            control(0x0C, "chunk_batch_start"),
            stateful(0x0D, "chunks_biomes", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x0E, "clear_titles", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x0F, "command_suggestions", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x10, "commands", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x11, "container_close", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x12, "container_set_content", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x13, "container_set_data", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x14, "container_set_slot", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x15, "cookie_request"),
            stateful(0x16, "cooldown", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x17, "custom_chat_completions", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            configurable(0x18, "custom_payload", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x19, "damage_event", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            control(0x1A, "debug/block_value"),
            control(0x1B, "debug/chunk_value"),
            control(0x1C, "debug/entity_value"),
            control(0x1D, "debug/event"),
            control(0x1E, "debug_sample"),
            ephemeral(0x1F, "delete_chat", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x20, "disconnect"),
            ephemeral(0x21, "disguised_chat", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x22, "entity_event", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x23, "entity_position_sync", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x24, "explode", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x25, "add_transient_block", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x26, "forget_level_chunk", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x27, "game_event", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x28, "game_rule_values", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x29, "game_test_highlight_pos"),
            stateful(0x2A, "mount_screen_open", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x2B, "hurt_animation", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x2C, "initialize_border", PacketDescriptor.PlaybackScope.SAFE),
            control(0x2D, "keep_alive"),
            stateful(0x2E, "level_chunk_with_light", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x2F, "level_event", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x30, "level_particles", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x31, "light_update", PacketDescriptor.PlaybackScope.SAFE),
            control(0x32, "login"),
            ephemeral(0x33, "low_disk_space_warning", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x34, "map_item_data", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x35, "merchant_offers", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x36, "move_entity_pos", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x37, "move_entity_pos_rot", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x38, "move_minecart_along_track", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x39, "move_entity_rot", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x3A, "move_vehicle", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x3B, "open_book", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x3C, "open_screen", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x3D, "open_sign_editor", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x3E, "ping"),
            control(0x3F, "pong_response"),
            ephemeral(0x40, "place_ghost_recipe", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x41, "player_abilities", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            configurable(0x42, "player_chat", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x43, "player_combat_end", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x44, "player_combat_enter", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x45, "player_combat_kill", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x46, "player_info_remove", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x47, "player_info_update", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x48, "player_look_at", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x49, "player_position", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4A, "player_rotation", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4B, "recipe_book_add", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4C, "recipe_book_remove", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4D, "recipe_book_settings", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4E, "remove_entities", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x4F, "remove_mob_effect", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x50, "reset_score", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x51, "resource_pack_pop"),
            control(0x52, "resource_pack_push"),
            stateful(0x53, "post_effects", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x54, "respawn", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x55, "rotate_head", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x56, "section_blocks_update", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x57, "select_advancements_tab", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x58, "server_data", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x59, "set_action_bar_text", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x5A, "set_border_center", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5B, "set_border_lerp_size", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5C, "set_border_size", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5D, "set_border_warning_delay", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5E, "set_border_warning_distance", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5F, "set_camera", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x60, "set_chunk_cache_center", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x61, "set_chunk_cache_radius", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x62, "set_cursor_item", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x63, "set_default_spawn_position", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x64, "set_display_objective", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x65, "set_entity_data", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x66, "set_entity_link", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x67, "set_entity_motion", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x68, "set_equipment", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x69, "set_experience", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x6A, "set_health", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x6B, "set_held_slot", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x6C, "set_objective", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x6D, "set_passengers", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x6E, "set_player_inventory", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x6F, "set_player_team", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x70, "set_score", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x71, "set_simulation_distance", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x72, "set_subtitle_text", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x73, "set_time", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x74, "set_title_text", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x75, "set_titles_animation", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x76, "sound_entity", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x77, "sound", PacketDescriptor.PlaybackScope.SAFE),
            control(0x78, "start_configuration"),
            ephemeral(0x79, "stop_sound", PacketDescriptor.PlaybackScope.SAFE),
            control(0x7A, "store_cookie"),
            ephemeral(0x7B, "swing_animation", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            configurable(0x7C, "system_chat", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x7D, "tab_list", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x7E, "tag_query"),
            ephemeral(0x7F, "take_item_entity", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x80, "teleport_entity", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            control(0x81, "test_instance_block_status"),
            control(0x82, "ticking_state"),
            control(0x83, "ticking_step"),
            control(0x84, "transfer"),
            stateful(0x85, "update_advancements", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x86, "update_attributes", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x87, "update_mob_effect", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x88, "update_recipes", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x89, "update_tags", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x8A, "projectile_power", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            control(0x8B, "custom_report_details"),
            stateful(0x8C, "server_links", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x8D, "waypoint", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x8E, "clear_dialog", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x8F, "show_dialog", PacketDescriptor.PlaybackScope.VIEWER_LOCAL));

    private static final Map<String, ExpectedPacket> EXPECTED_BY_TYPE = IMPLEMENTED_PACKETS.stream()
            .collect(Collectors.toUnmodifiableMap(
                    ExpectedPacket::typeName, Function.identity()));

    @Test
    void matchesTheCatalogAndRegistryAgainstTheImplementedPacketManifest() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        var catalog = Paper26_3ClientboundPacketCatalog.catalog();
        assertEquals(144, catalog.definitions().size());
        assertEquals(
                IMPLEMENTED_PACKETS.stream().map(ExpectedPacket::typeName).toList(),
                catalog.definitions().stream().map(ClientboundPacketDefinition::typeName).toList());

        var registry = Paper26_3PacketRegistry.discover();
        for (ExpectedPacket expected : IMPLEMENTED_PACKETS) {
            var descriptor = registry.find(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            expected.packetId())
                    .orElseThrow();
            assertEquals(expected.typeName(), descriptor.typeName());
        }

        for (var descriptor : registry.descriptors()) {
            if (descriptor.phase() != PacketPhase.PLAY) {
                continue;
            }

            ExpectedPacket expected = EXPECTED_BY_TYPE.get(descriptor.typeName());
            assertTrue(expected != null, "unexpected live PLAY packet: " + descriptor.typeName());
            var definition = catalog.require(descriptor.typeName());
            assertEquals(expected.packetId(), descriptor.packetId());
            assertEquals(descriptor.packetId(), definition.packetId());
            assertTrue(Paper26_3PacketRegistry.explicitlyClassifies(descriptor.typeName()));
            if (descriptor.checkpointRelevant()) {
                assertTrue(definition.checkpointFamily().isPresent(),
                        "checkpoint family missing for " + descriptor.typeName());
            }

            assertEquals(expected.disposition(), descriptor.disposition(), descriptor.typeName());
            assertEquals(expected.captureByDefault(), descriptor.captureByDefault(), descriptor.typeName());
            assertEquals(expected.replayable(), descriptor.replayable(), descriptor.typeName());
            assertEquals(expected.checkpointRelevant(), descriptor.checkpointRelevant(), descriptor.typeName());
            assertEquals(expected.playbackScope(), descriptor.playbackScope(), descriptor.typeName());
        }

        List<String> nonCheckpointPacketsWithCheckpointFamily = registry.descriptors().stream()
                .filter(descriptor -> descriptor.phase() == PacketPhase.PLAY)
                .filter(descriptor -> !descriptor.checkpointRelevant())
                .filter(descriptor -> catalog.require(descriptor.typeName())
                        .checkpointFamily().isPresent())
                .map(PacketDescriptor::typeName)
                .toList();
        assertTrue(nonCheckpointPacketsWithCheckpointFamily.isEmpty(),
                "non-checkpoint packets must not declare checkpoint families: "
                        + nonCheckpointPacketsWithCheckpointFamily);
    }

    private static ExpectedPacket control(int packetId, String identifier) {
        return new ExpectedPacket(
                packetId,
                typeName(identifier),
                PacketDisposition.CONTROL,
                true,
                false,
                false,
                PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static ExpectedPacket stateful(
            int packetId, String identifier, PacketDescriptor.PlaybackScope scope) {
        return new ExpectedPacket(
                packetId,
                typeName(identifier),
                PacketDisposition.STATEFUL,
                true,
                true,
                scope != PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                scope);
    }

    private static ExpectedPacket ephemeral(
            int packetId, String identifier, PacketDescriptor.PlaybackScope scope) {
        return new ExpectedPacket(
                packetId,
                typeName(identifier),
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                scope);
    }

    private static ExpectedPacket configurable(
            int packetId, String identifier, PacketDescriptor.PlaybackScope scope) {
        return new ExpectedPacket(
                packetId,
                typeName(identifier),
                PacketDisposition.CONFIGURABLE,
                true,
                true,
                false,
                scope);
    }

    private static String typeName(String identifier) {
        return "play.clientbound.minecraft:" + identifier;
    }

    private record ExpectedPacket(
            int packetId,
            String typeName,
            PacketDisposition disposition,
            boolean captureByDefault,
            boolean replayable,
            boolean checkpointRelevant,
            PacketDescriptor.PlaybackScope playbackScope) {}
}
