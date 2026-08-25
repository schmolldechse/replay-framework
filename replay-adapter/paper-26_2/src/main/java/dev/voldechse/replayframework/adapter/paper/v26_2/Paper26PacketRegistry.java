package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable Paper 26.2 implementation of the adapter packet registry. */
public final class Paper26PacketRegistry implements PacketRegistry {

    private static final String TYPE_PREFIX = "play.clientbound.";
    private static final String CODEC_PREFIX = "paper-26.2/";

    private static final Map<String, Classification> CLASSIFICATIONS = Map.ofEntries(
            // Framing and connection-control packets are never replay data.
            Map.entry(type("minecraft:bundle_delimiter"), control()),
            Map.entry(type("minecraft:cookie_request"), control()),
            Map.entry(type("minecraft:disconnect"), control()),
            Map.entry(type("minecraft:keep_alive"), control()),
            Map.entry(type("minecraft:ping"), control()),
            Map.entry(type("minecraft:pong_response"), control()),
            Map.entry(type("minecraft:resource_pack_pop"), control()),
            Map.entry(type("minecraft:resource_pack_push"), control()),
            Map.entry(type("minecraft:start_configuration"), control()),
            Map.entry(type("minecraft:store_cookie"), control()),
            Map.entry(type("minecraft:custom_report_details"), control()),
            Map.entry(type("minecraft:chunk_batch_start"), control()),
            Map.entry(type("minecraft:chunk_batch_finished"), control()),
            Map.entry(type("minecraft:transfer"), control()),
            Map.entry(type("minecraft:ticking_state"), control()),
            Map.entry(type("minecraft:ticking_step"), control()),

            // Chunk, light, block and world state is needed for checkpoint rebuilds.
            Map.entry(type("minecraft:chunks_biomes"), stateful()),
            Map.entry(type("minecraft:level_chunk_with_light"), stateful()),
            Map.entry(type("minecraft:light_update"), stateful()),
            Map.entry(type("minecraft:block_entity_data"), stateful()),
            Map.entry(type("minecraft:block_update"), stateful()),
            Map.entry(type("minecraft:forget_level_chunk"), stateful()),
            Map.entry(type("minecraft:section_blocks_update"), stateful()),
            Map.entry(type("minecraft:block_changed_ack"), stateful()),
            Map.entry(type("minecraft:change_difficulty"), stateful()),
            Map.entry(type("minecraft:game_rule_values"), stateful()),
            Map.entry(type("minecraft:initialize_border"), stateful()),
            Map.entry(type("minecraft:set_border_center"), stateful()),
            Map.entry(type("minecraft:set_border_lerp_size"), stateful()),
            Map.entry(type("minecraft:set_border_size"), stateful()),
            Map.entry(type("minecraft:set_border_warning_delay"), stateful()),
            Map.entry(type("minecraft:set_border_warning_distance"), stateful()),
            Map.entry(type("minecraft:set_default_spawn_position"), stateful()),
            Map.entry(type("minecraft:set_time"), stateful()),

            // Entity lifecycle, movement and durable entity attributes are stateful.
            Map.entry(type("minecraft:add_entity"), stateful()),
            Map.entry(type("minecraft:entity_position_sync"), stateful()),
            Map.entry(type("minecraft:move_entity_pos"), stateful()),
            Map.entry(type("minecraft:move_entity_pos_rot"), stateful()),
            Map.entry(type("minecraft:move_entity_rot"), stateful()),
            Map.entry(type("minecraft:move_minecart_along_track"), stateful()),
            Map.entry(type("minecraft:move_vehicle"), stateful()),
            Map.entry(type("minecraft:remove_entities"), stateful()),
            Map.entry(type("minecraft:rotate_head"), stateful()),
            Map.entry(type("minecraft:set_camera"), stateful()),
            Map.entry(type("minecraft:set_entity_data"), stateful()),
            Map.entry(type("minecraft:set_entity_link"), stateful()),
            Map.entry(type("minecraft:set_entity_motion"), stateful()),
            Map.entry(type("minecraft:set_equipment"), stateful()),
            Map.entry(type("minecraft:set_experience"), stateful()),
            Map.entry(type("minecraft:set_health"), stateful()),
            Map.entry(type("minecraft:set_passengers"), stateful()),
            Map.entry(type("minecraft:teleport_entity"), stateful()),
            Map.entry(type("minecraft:update_attributes"), stateful()),
            Map.entry(type("minecraft:update_mob_effect"), stateful()),
            Map.entry(type("minecraft:remove_mob_effect"), stateful()),
            Map.entry(type("minecraft:projectile_power"), stateful()),
            Map.entry(type("minecraft:waypoint"), stateful()),
            Map.entry(type("minecraft:player_abilities"), stateful()),
            Map.entry(type("minecraft:player_position"), stateful()),
            Map.entry(type("minecraft:player_rotation"), stateful()),
            Map.entry(type("minecraft:respawn"), stateful()),
            Map.entry(type("minecraft:login"), stateful()),

            // Inventory, recipe, command, team, score and tab-list state persists.
            Map.entry(type("minecraft:container_close"), stateful()),
            Map.entry(type("minecraft:container_set_content"), stateful()),
            Map.entry(type("minecraft:container_set_data"), stateful()),
            Map.entry(type("minecraft:container_set_slot"), stateful()),
            Map.entry(type("minecraft:cooldown"), stateful()),
            Map.entry(type("minecraft:command_suggestions"), ephemeral()),
            Map.entry(type("minecraft:commands"), stateful()),
            Map.entry(type("minecraft:custom_chat_completions"), stateful()),
            Map.entry(type("minecraft:merchant_offers"), stateful()),
            Map.entry(type("minecraft:mount_screen_open"), stateful()),
            Map.entry(type("minecraft:open_screen"), stateful()),
            Map.entry(type("minecraft:place_ghost_recipe"), stateful()),
            Map.entry(type("minecraft:recipe_book_add"), stateful()),
            Map.entry(type("minecraft:recipe_book_remove"), stateful()),
            Map.entry(type("minecraft:recipe_book_settings"), stateful()),
            Map.entry(type("minecraft:set_cursor_item"), stateful()),
            Map.entry(type("minecraft:set_held_slot"), stateful()),
            Map.entry(type("minecraft:set_player_inventory"), stateful()),
            Map.entry(type("minecraft:player_info_remove"), stateful()),
            Map.entry(type("minecraft:player_info_update"), stateful()),
            Map.entry(type("minecraft:set_objective"), stateful()),
            Map.entry(type("minecraft:set_display_objective"), stateful()),
            Map.entry(type("minecraft:set_player_team"), stateful()),
            Map.entry(type("minecraft:set_score"), stateful()),
            Map.entry(type("minecraft:reset_score"), stateful()),
            Map.entry(type("minecraft:boss_event"), stateful()),
            Map.entry(type("minecraft:tab_list"), stateful()),
            Map.entry(type("minecraft:update_advancements"), stateful()),
            Map.entry(type("minecraft:select_advancements_tab"), configurable()),
            Map.entry(type("minecraft:update_recipes"), stateful()),
            Map.entry(type("minecraft:update_tags"), stateful()),
            Map.entry(type("minecraft:map_item_data"), stateful()),
            Map.entry(type("minecraft:player_look_at"), ephemeral()),
            Map.entry(type("minecraft:server_data"), configurable()),
            Map.entry(type("minecraft:set_chunk_cache_center"), stateful()),
            Map.entry(type("minecraft:set_chunk_cache_radius"), stateful()),
            Map.entry(type("minecraft:set_simulation_distance"), stateful()),

            // Chat, titles and other presentation packets remain policy-configurable.
            Map.entry(type("minecraft:custom_payload"), configurable()),
            Map.entry(type("minecraft:delete_chat"), configurable()),
            Map.entry(type("minecraft:disguised_chat"), configurable()),
            Map.entry(type("minecraft:player_chat"), configurable()),
            Map.entry(type("minecraft:system_chat"), configurable()),
            Map.entry(type("minecraft:set_action_bar_text"), configurable()),
            Map.entry(type("minecraft:set_subtitle_text"), configurable()),
            Map.entry(type("minecraft:set_title_text"), configurable()),
            Map.entry(type("minecraft:set_titles_animation"), configurable()),
            Map.entry(type("minecraft:clear_titles"), configurable()),
            Map.entry(type("minecraft:open_book"), configurable()),
            Map.entry(type("minecraft:open_sign_editor"), configurable()),
            Map.entry(type("minecraft:server_links"), configurable()),
            Map.entry(type("minecraft:clear_dialog"), configurable()),
            Map.entry(type("minecraft:show_dialog"), configurable()),

            // Short-lived effects are replayed in order but never copied to checkpoints.
            Map.entry(type("minecraft:animate"), ephemeral()),
            Map.entry(type("minecraft:award_stats"), stateful()),
            Map.entry(type("minecraft:block_destruction"), ephemeral()),
            Map.entry(type("minecraft:block_event"), ephemeral()),
            Map.entry(type("minecraft:damage_event"), ephemeral()),
            Map.entry(type("minecraft:entity_event"), ephemeral()),
            Map.entry(type("minecraft:explode"), ephemeral()),
            Map.entry(type("minecraft:game_event"), ephemeral()),
            Map.entry(type("minecraft:hurt_animation"), ephemeral()),
            Map.entry(type("minecraft:level_event"), ephemeral()),
            Map.entry(type("minecraft:level_particles"), ephemeral()),
            Map.entry(type("minecraft:sound"), ephemeral()),
            Map.entry(type("minecraft:sound_entity"), ephemeral()),
            Map.entry(type("minecraft:stop_sound"), ephemeral()),
            Map.entry(type("minecraft:take_item_entity"), ephemeral()),
            Map.entry(type("minecraft:player_combat_end"), ephemeral()),
            Map.entry(type("minecraft:player_combat_enter"), ephemeral()),
            Map.entry(type("minecraft:player_combat_kill"), ephemeral()),

            // Known packets without a safe replay contract are explicitly blocked.
            Map.entry(type("minecraft:debug/block_value"), unsupported()),
            Map.entry(type("minecraft:debug/chunk_value"), unsupported()),
            Map.entry(type("minecraft:debug/entity_value"), unsupported()),
            Map.entry(type("minecraft:debug/event"), unsupported()),
            Map.entry(type("minecraft:debug_sample"), unsupported()),
            Map.entry(type("minecraft:game_test_highlight_pos"), unsupported()),
            Map.entry(type("minecraft:low_disk_space_warning"), unsupported()),
            Map.entry(type("minecraft:tag_query"), unsupported()),
            Map.entry(type("minecraft:test_instance_block_status"), unsupported()));

    private final PacketRegistry delegate;

    private Paper26PacketRegistry(PacketRegistry delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Discovers and classifies the live Paper 26.2 PLAY clientbound registry.
     *
     * @return immutable classified registry
     */
    public static Paper26PacketRegistry discover() {
        return fromSnapshot(new Paper26ProtocolIntrospector().inspect());
    }

    /**
     * Creates a registry from the NMS-free snapshot used by the focused adapter test.
     *
     * @param snapshot validated Paper snapshot
     * @return immutable classified registry
     * @throws IncompatibleAdapterException when any entry is not an explicit 26.2 type
     */
    static Paper26PacketRegistry fromSnapshot(
            Paper26ProtocolIntrospector.ProtocolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!"26.2".equals(snapshot.minecraftVersion())) {
            throw incompatible("unsupported Paper Minecraft version", null);
        }

        List<PacketDescriptor> descriptors = new ArrayList<>(snapshot.clientboundPlayPackets().size());
        for (Paper26ProtocolIntrospector.PacketType packet : snapshot.clientboundPlayPackets()) {
            validatePacket(packet);
            Classification classification = CLASSIFICATIONS.get(packet.typeName());
            if (classification == null) {
                throw incompatible("unclassified Paper PLAY packet type " + packet.typeName(), null);
            }
            String expectedCodecKey = CODEC_PREFIX + packet.typeName();
            if (!expectedCodecKey.equals(packet.codecKey())) {
                throw incompatible("Paper packet codec key mismatch for " + packet.typeName(), null);
            }
            try {
                descriptors.add(new PacketDescriptor(
                        packet.typeName(),
                        packet.phase(),
                        packet.direction(),
                        packet.packetId(),
                        classification.disposition(),
                        classification.captureByDefault(),
                        classification.replayable(),
                        classification.checkpointRelevant(),
                        packet.codecKey()));
            } catch (IllegalArgumentException exception) {
                throw incompatible("invalid Paper packet descriptor for " + packet.typeName(), exception);
            }
        }

        try {
            return new Paper26PacketRegistry(PacketRegistry.of(descriptors));
        } catch (IllegalArgumentException exception) {
            throw incompatible("Paper PLAY packet registry is not unique", exception);
        }
    }

    @Override
    public List<PacketDescriptor> descriptors() {
        return delegate.descriptors();
    }

    @Override
    public Optional<PacketDescriptor> find(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return delegate.find(phase, direction, packetId);
    }

    @Override
    public boolean captureAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return delegate.captureAllowed(phase, direction, packetId);
    }

    @Override
    public boolean replayAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return delegate.replayAllowed(phase, direction, packetId);
    }

    @Override
    public String fingerprint() {
        return delegate.fingerprint();
    }

    private static void validatePacket(Paper26ProtocolIntrospector.PacketType packet) {
        if (packet == null
                || packet.phase() != PacketPhase.PLAY
                || packet.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || packet.packetId() < 0) {
            throw incompatible("Paper snapshot contains a non-PLAY clientbound packet", null);
        }
    }

    private static String type(String identifier) {
        return TYPE_PREFIX + identifier;
    }

    private static Classification stateful() {
        return new Classification(PacketDisposition.STATEFUL, true, true, true);
    }

    private static Classification ephemeral() {
        return new Classification(PacketDisposition.EPHEMERAL, true, true, false);
    }

    private static Classification configurable() {
        return new Classification(PacketDisposition.CONFIGURABLE, true, true, false);
    }

    /** CONTROL means protocol/connection mechanics, never replay data. */
    private static Classification control() {
        return new Classification(PacketDisposition.CONTROL, false, false, false);
    }

    /** UNSUPPORTED means known but intentionally blocked until a safe contract exists. */
    private static Classification unsupported() {
        return new Classification(PacketDisposition.UNSUPPORTED, false, false, false);
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    private record Classification(
            PacketDisposition disposition,
            boolean captureByDefault,
            boolean replayable,
            boolean checkpointRelevant) {

        private Classification {
            Objects.requireNonNull(disposition, "disposition");
            if ((disposition == PacketDisposition.CONTROL
                    || disposition == PacketDisposition.UNSUPPORTED)
                    && (captureByDefault || replayable || checkpointRelevant)) {
                throw new IllegalArgumentException(
                        disposition + " packets must be disabled at registry construction");
            }
        }
    }
}
