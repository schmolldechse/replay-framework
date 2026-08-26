package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26ReplayPacketRewriter;
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
            // This acknowledgement carries no reconstructible client state.
            // It remains registered so capture and playback reject it
            // explicitly instead of treating it as a durable checkpoint value.
            Map.entry(type("minecraft:block_changed_ack"), unsupported()),
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
            Map.entry(type("minecraft:move_minecart_along_track"), unsupported()),
            Map.entry(type("minecraft:move_vehicle"), viewerLocalStateful()),
            Map.entry(type("minecraft:remove_entities"), stateful()),
            Map.entry(type("minecraft:rotate_head"), stateful()),
            Map.entry(type("minecraft:set_camera"), viewerLocalStateful()),
            Map.entry(type("minecraft:set_entity_data"), stateful()),
            Map.entry(type("minecraft:set_entity_link"), unsupported()),
            Map.entry(type("minecraft:set_entity_motion"), stateful()),
            Map.entry(type("minecraft:set_equipment"), stateful()),
            Map.entry(type("minecraft:set_experience"), viewerLocalStateful()),
            Map.entry(type("minecraft:set_health"), viewerLocalStateful()),
            Map.entry(type("minecraft:set_passengers"), stateful()),
            Map.entry(type("minecraft:teleport_entity"), stateful()),
            Map.entry(type("minecraft:update_attributes"), stateful()),
            Map.entry(type("minecraft:update_mob_effect"), stateful()),
            Map.entry(type("minecraft:remove_mob_effect"), stateful()),
            Map.entry(type("minecraft:projectile_power"), stateful()),
            Map.entry(type("minecraft:waypoint"), unsupported()),
            Map.entry(type("minecraft:player_abilities"), viewerLocalStateful()),
            Map.entry(type("minecraft:player_position"), viewerLocalStateful()),
            Map.entry(type("minecraft:player_rotation"), viewerLocalStateful()),
            Map.entry(type("minecraft:respawn"), viewerLocalStateful()),
            // A PLAY login is a connection bootstrap and cannot be sent to an
            // already connected live viewer.
            Map.entry(type("minecraft:login"), control()),

            // Inventory, recipe, command, team, score and tab-list state persists.
            Map.entry(type("minecraft:container_close"), viewerLocalStateful()),
            Map.entry(type("minecraft:container_set_content"), viewerLocalStateful()),
            Map.entry(type("minecraft:container_set_data"), viewerLocalStateful()),
            Map.entry(type("minecraft:container_set_slot"), viewerLocalStateful()),
            Map.entry(type("minecraft:cooldown"), stateful()),
            Map.entry(type("minecraft:command_suggestions"), ephemeral()),
            Map.entry(type("minecraft:commands"), stateful()),
            Map.entry(type("minecraft:custom_chat_completions"), stateful()),
            Map.entry(type("minecraft:merchant_offers"), stateful()),
            Map.entry(type("minecraft:mount_screen_open"), viewerLocalStateful()),
            Map.entry(type("minecraft:open_screen"), viewerLocalStateful()),
            Map.entry(type("minecraft:place_ghost_recipe"), stateful()),
            Map.entry(type("minecraft:recipe_book_add"), stateful()),
            Map.entry(type("minecraft:recipe_book_remove"), stateful()),
            Map.entry(type("minecraft:recipe_book_settings"), stateful()),
            Map.entry(type("minecraft:set_cursor_item"), viewerLocalStateful()),
            Map.entry(type("minecraft:set_held_slot"), viewerLocalStateful()),
            Map.entry(type("minecraft:set_player_inventory"), viewerLocalStateful()),
            Map.entry(type("minecraft:player_info_remove"), stateful()),
            Map.entry(type("minecraft:player_info_update"), stateful()),
            Map.entry(type("minecraft:set_objective"), unsupported()),
            Map.entry(type("minecraft:set_display_objective"), unsupported()),
            Map.entry(type("minecraft:set_player_team"), unsupported()),
            Map.entry(type("minecraft:set_score"), stateful()),
            Map.entry(type("minecraft:reset_score"), stateful()),
            Map.entry(type("minecraft:boss_event"), stateful()),
            Map.entry(type("minecraft:tab_list"), viewerLocalStateful()),
            Map.entry(type("minecraft:update_advancements"), stateful()),
            Map.entry(type("minecraft:select_advancements_tab"), configurable()),
            Map.entry(type("minecraft:update_recipes"), stateful()),
            Map.entry(type("minecraft:update_tags"), stateful()),
            Map.entry(type("minecraft:map_item_data"), stateful()),
            Map.entry(type("minecraft:player_look_at"), viewerLocalEphemeral()),
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
            Map.entry(type("minecraft:set_action_bar_text"), viewerLocalConfigurable()),
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
            Map.entry(type("minecraft:animate"), unsupported()),
            Map.entry(type("minecraft:award_stats"), stateful()),
            Map.entry(type("minecraft:block_destruction"), unsupported()),
            Map.entry(type("minecraft:block_event"), ephemeral()),
            Map.entry(type("minecraft:damage_event"), ephemeral()),
            Map.entry(type("minecraft:entity_event"), unsupported()),
            Map.entry(type("minecraft:explode"), ephemeral()),
            Map.entry(type("minecraft:game_event"), ephemeral()),
            Map.entry(type("minecraft:hurt_animation"), ephemeral()),
            Map.entry(type("minecraft:level_event"), ephemeral()),
            Map.entry(type("minecraft:level_particles"), ephemeral()),
            Map.entry(type("minecraft:sound"), ephemeral()),
            Map.entry(type("minecraft:sound_entity"), unsupported()),
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
     * Discovers and classifies every live Paper 26.2 clientbound registry.
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
     * @throws IncompatibleAdapterException when Paper exposes an invalid clientbound entry
     */
    static Paper26PacketRegistry fromSnapshot(
            Paper26ProtocolIntrospector.ProtocolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!"26.2".equals(snapshot.minecraftVersion())) {
            throw incompatible("unsupported Paper Minecraft version", null);
        }

        List<PacketDescriptor> descriptors = new ArrayList<>(snapshot.clientboundPackets().size());
        for (Paper26ProtocolIntrospector.PacketType packet : snapshot.clientboundPackets()) {
            validatePacket(packet);
            Classification classification = packet.phase() == PacketPhase.PLAY
                    ? CLASSIFICATIONS.getOrDefault(packet.typeName(), captureOnly())
                    : captureOnly();
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
                        packet.codecKey(),
                        playbackScope(packet.typeName(), classification)));
            } catch (IllegalArgumentException exception) {
                throw incompatible("invalid Paper packet descriptor for " + packet.typeName(), exception);
            }
        }

        try {
            return new Paper26PacketRegistry(PacketRegistry.of(descriptors));
        } catch (IllegalArgumentException exception) {
            throw incompatible("Paper clientbound packet registry is not unique", exception);
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
                || packet.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || packet.packetId() < 0) {
            throw incompatible("Paper snapshot contains an invalid clientbound packet", null);
        }
    }

    private static String type(String identifier) {
        return TYPE_PREFIX + identifier;
    }

    private static Classification stateful() {
        return new Classification(PacketDisposition.STATEFUL, true, true, true);
    }

    private static Classification viewerLocalStateful() {
        return new Classification(PacketDisposition.STATEFUL, true, true, false);
    }

    private static Classification ephemeral() {
        return new Classification(PacketDisposition.EPHEMERAL, true, true, false);
    }

    private static Classification configurable() {
        return new Classification(PacketDisposition.CONFIGURABLE, true, true, false);
    }

    private static Classification viewerLocalEphemeral() {
        return new Classification(PacketDisposition.EPHEMERAL, true, true, false);
    }

    private static Classification viewerLocalConfigurable() {
        return new Classification(PacketDisposition.CONFIGURABLE, true, true, false);
    }

    /** CONTROL means captured diagnostics/protocol data, never replay data. */
    private static Classification control() {
        return new Classification(PacketDisposition.CONTROL, true, false, false);
    }

    /** UNSUPPORTED means captured but blocked until a safe playback contract exists. */
    private static Classification unsupported() {
        return new Classification(PacketDisposition.UNSUPPORTED, true, false, false);
    }

    /** All non-PLAY and newly discovered packets are retained but never replayed implicitly. */
    private static Classification captureOnly() {
        return new Classification(PacketDisposition.CONTROL, true, false, false);
    }

    private static PacketDescriptor.PlaybackScope playbackScope(
            String typeName,
            Classification classification) {
        if (classification.disposition() == PacketDisposition.CONTROL
                || classification.disposition() == PacketDisposition.UNSUPPORTED) {
            return PacketDescriptor.PlaybackScope.UNSAFE;
        }
        String identifier = typeName.substring(TYPE_PREFIX.length());
        if (Paper26ReplayPacketRewriter.isViewerLocal(identifier)) {
            return PacketDescriptor.PlaybackScope.VIEWER_LOCAL;
        }
        if (Paper26ReplayPacketRewriter.isIdentityBearing(identifier)) {
            return PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES;
        }
        return PacketDescriptor.PlaybackScope.SAFE;
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
                    && (replayable || checkpointRelevant)) {
                throw new IllegalArgumentException(
                        disposition + " packets cannot be replayable or checkpoint-relevant");
            }
        }
    }
}
