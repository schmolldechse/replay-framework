package dev.voldechse.replayframework.adapter.paper.v26_2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
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
            ephemeral(0x21, "disguised_chat", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x22, "entity_event", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x23, "entity_position_sync", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x24, "explode", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x25, "forget_level_chunk", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x26, "game_event", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x27, "game_rule_values", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x28, "game_test_highlight_pos"),
            stateful(0x29, "mount_screen_open", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x2A, "hurt_animation", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x2B, "initialize_border", PacketDescriptor.PlaybackScope.SAFE),
            control(0x2C, "keep_alive"),
            stateful(0x2D, "level_chunk_with_light", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x2E, "level_event", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x2F, "level_particles", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x30, "light_update", PacketDescriptor.PlaybackScope.SAFE),
            control(0x31, "login"),
            ephemeral(0x32, "low_disk_space_warning", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x33, "map_item_data", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x34, "merchant_offers", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x35, "move_entity_pos", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x36, "move_entity_pos_rot", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x37, "move_minecart_along_track", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x38, "move_entity_rot", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x39, "move_vehicle", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x3A, "open_book", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x3B, "open_screen", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x3C, "open_sign_editor", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x3D, "ping"),
            control(0x3E, "pong_response"),
            ephemeral(0x3F, "place_ghost_recipe", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x40, "player_abilities", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            configurable(0x41, "player_chat", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x42, "player_combat_end", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x43, "player_combat_enter", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x44, "player_combat_kill", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x45, "player_info_remove", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x46, "player_info_update", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            ephemeral(0x47, "player_look_at", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x48, "player_position", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x49, "player_rotation", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4A, "recipe_book_add", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4B, "recipe_book_remove", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4C, "recipe_book_settings", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x4D, "remove_entities", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x4E, "remove_mob_effect", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x4F, "reset_score", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            control(0x50, "resource_pack_pop"),
            control(0x51, "resource_pack_push"),
            stateful(0x52, "respawn", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x53, "rotate_head", PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
            stateful(0x54, "section_blocks_update", PacketDescriptor.PlaybackScope.SAFE),
            ephemeral(0x55, "select_advancements_tab", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x56, "server_data", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            ephemeral(0x57, "set_action_bar_text", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x58, "set_border_center", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x59, "set_border_lerp_size", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5A, "set_border_size", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5B, "set_border_warning_delay", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5C, "set_border_warning_distance", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5D, "set_camera", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x5E, "set_chunk_cache_center", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x5F, "set_chunk_cache_radius", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x60, "set_cursor_item", PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
            stateful(0x61, "set_default_spawn_position", PacketDescriptor.PlaybackScope.SAFE),
            stateful(0x62, "set_display_objective", PacketDescriptor.PlaybackScope.VIEWER_LOCAL));

    private static final Map<String, ExpectedPacket> EXPECTED_BY_TYPE = IMPLEMENTED_PACKETS.stream()
            .collect(Collectors.toUnmodifiableMap(
                    ExpectedPacket::typeName, Function.identity()));

    @Test
    void matchesTheCatalogAndRegistryAgainstTheImplementedPacketManifest() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        var catalog = Paper26_2PacketCatalog.catalog();
        assertEquals(
                IMPLEMENTED_PACKETS.stream().map(ExpectedPacket::typeName).toList(),
                catalog.definitions().stream().map(PacketDefinition::typeName).toList());

        var registry = Paper26_2PacketRegistry.discover();
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
            assertEquals(
                    expected != null,
                    Paper26_2PacketRegistry.explicitlyClassifies(descriptor.typeName()));
            if (expected == null) {
                expected = unsupported(descriptor.packetId(), descriptor.typeName());
            } else {
                assertEquals(expected.packetId(), descriptor.packetId());
            }

            assertEquals(expected.disposition(), descriptor.disposition(), descriptor.typeName());
            assertEquals(expected.captureByDefault(), descriptor.captureByDefault(), descriptor.typeName());
            assertEquals(expected.replayable(), descriptor.replayable(), descriptor.typeName());
            assertEquals(expected.checkpointRelevant(), descriptor.checkpointRelevant(), descriptor.typeName());
            assertEquals(expected.playbackScope(), descriptor.playbackScope(), descriptor.typeName());
        }
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

    private static ExpectedPacket unsupported(int packetId, String typeName) {
        return new ExpectedPacket(
                packetId,
                typeName,
                PacketDisposition.UNSUPPORTED,
                true,
                false,
                false,
                PacketDescriptor.PlaybackScope.UNSAFE);
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
