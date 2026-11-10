package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.playback.NeutralObserverPolicy;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Single Paper 26.3 source of descriptor policy and decoded packet behavior. */
public final class Paper26_3PacketCatalog {

    private static final Set<String> IDENTITY_BEARING = Set.of(
            "minecraft:add_entity",
            "minecraft:animate",
            "minecraft:block_destruction",
            "minecraft:boss_event",
            "minecraft:damage_event",
            "minecraft:entity_event",
            "minecraft:entity_position_sync",
            "minecraft:hurt_animation",
            "minecraft:move_entity_pos",
            "minecraft:move_entity_pos_rot",
            "minecraft:move_entity_rot",
            "minecraft:move_minecart_along_track",
            "minecraft:remove_entities",
            "minecraft:swing_animation",
            "minecraft:rotate_head",
            "minecraft:set_entity_data",
            "minecraft:set_entity_link",
            "minecraft:set_entity_motion",
            "minecraft:set_equipment",
            "minecraft:set_passengers",
            "minecraft:teleport_entity",
            "minecraft:update_attributes",
            "minecraft:update_mob_effect",
            "minecraft:remove_mob_effect",
            "minecraft:player_info_remove",
            "minecraft:player_info_update",
            "minecraft:player_chat",
            "minecraft:projectile_power",
            "minecraft:take_item_entity",
            "minecraft:sound_entity");

    private static final Map<String, RawClassification> CLASSIFICATIONS = Map.ofEntries(
            entry("minecraft:bundle_delimiter", control()),
            entry("minecraft:cookie_request", control()),
            entry("minecraft:disconnect", control()),
            entry("minecraft:keep_alive", control()),
            entry("minecraft:ping", control()),
            entry("minecraft:pong_response", control()),
            entry("minecraft:resource_pack_pop", control()),
            entry("minecraft:resource_pack_push", control()),
            entry("minecraft:start_configuration", control()),
            entry("minecraft:store_cookie", control()),
            entry("minecraft:custom_report_details", control()),
            entry("minecraft:chunk_batch_start", control()),
            entry("minecraft:chunk_batch_finished", control()),
            entry("minecraft:transfer", control()),
            entry("minecraft:ticking_state", control()),
            entry("minecraft:ticking_step", control()),
            entry("minecraft:chunks_biomes", stateful()),
            entry("minecraft:level_chunk_with_light", stateful()),
            entry("minecraft:light_update", stateful()),
            entry("minecraft:block_entity_data", stateful()),
            entry("minecraft:block_update", stateful()),
            entry("minecraft:forget_level_chunk", stateful()),
            entry("minecraft:section_blocks_update", stateful()),
            entry("minecraft:block_changed_ack", unsupported()),
            entry("minecraft:change_difficulty", stateful()),
            entry("minecraft:game_rule_values", stateful()),
            entry("minecraft:initialize_border", stateful()),
            entry("minecraft:set_border_center", stateful()),
            entry("minecraft:set_border_lerp_size", stateful()),
            entry("minecraft:set_border_size", stateful()),
            entry("minecraft:set_border_warning_delay", stateful()),
            entry("minecraft:set_border_warning_distance", stateful()),
            entry("minecraft:set_default_spawn_position", stateful()),
            entry("minecraft:set_time", stateful()),
            entry("minecraft:add_entity", stateful()),
            entry("minecraft:entity_position_sync", stateful()),
            entry("minecraft:move_entity_pos", stateful()),
            entry("minecraft:move_entity_pos_rot", stateful()),
            entry("minecraft:move_entity_rot", stateful()),
            entry("minecraft:move_minecart_along_track", stateful()),
            entry("minecraft:move_vehicle", viewerLocalStateful()),
            entry("minecraft:remove_entities", stateful()),
            entry("minecraft:rotate_head", stateful()),
            entry("minecraft:set_camera", viewerLocalStateful()),
            entry("minecraft:set_entity_data", stateful()),
            entry("minecraft:set_entity_link", stateful()),
            entry("minecraft:set_entity_motion", stateful()),
            entry("minecraft:set_equipment", stateful()),
            entry("minecraft:set_experience", viewerLocalStateful()),
            entry("minecraft:set_health", viewerLocalStateful()),
            entry("minecraft:set_passengers", stateful()),
            entry("minecraft:teleport_entity", stateful()),
            entry("minecraft:update_attributes", stateful()),
            entry("minecraft:update_mob_effect", stateful()),
            entry("minecraft:remove_mob_effect", stateful()),
            entry("minecraft:projectile_power", stateful()),
            entry("minecraft:waypoint", viewerLocalStateful()),
            entry("minecraft:player_abilities", viewerLocalStateful()),
            entry("minecraft:player_position", viewerLocalStateful()),
            entry("minecraft:player_rotation", viewerLocalStateful()),
            entry("minecraft:respawn", viewerLocalStateful()),
            entry("minecraft:login", control()),
            entry("minecraft:container_close", viewerLocalStateful()),
            entry("minecraft:container_set_content", viewerLocalStateful()),
            entry("minecraft:container_set_data", viewerLocalStateful()),
            entry("minecraft:container_set_slot", viewerLocalStateful()),
            entry("minecraft:cooldown", stateful()),
            entry("minecraft:command_suggestions", ephemeral()),
            entry("minecraft:commands", stateful()),
            entry("minecraft:custom_chat_completions", stateful()),
            entry("minecraft:merchant_offers", stateful()),
            entry("minecraft:mount_screen_open", viewerLocalStateful()),
            entry("minecraft:open_screen", viewerLocalStateful()),
            entry("minecraft:place_ghost_recipe", stateful()),
            entry("minecraft:recipe_book_add", stateful()),
            entry("minecraft:recipe_book_remove", stateful()),
            entry("minecraft:recipe_book_settings", stateful()),
            entry("minecraft:set_cursor_item", viewerLocalStateful()),
            entry("minecraft:set_held_slot", viewerLocalStateful()),
            entry("minecraft:set_player_inventory", viewerLocalStateful()),
            entry("minecraft:player_info_remove", stateful()),
            entry("minecraft:player_info_update", stateful()),
            entry("minecraft:set_objective", viewerLocalStateful()),
            entry("minecraft:set_display_objective", viewerLocalStateful()),
            entry("minecraft:set_player_team", viewerLocalStateful()),
            entry("minecraft:set_score", viewerLocalStateful()),
            entry("minecraft:reset_score", viewerLocalStateful()),
            entry("minecraft:boss_event", stateful()),
            entry("minecraft:tab_list", viewerLocalStateful()),
            entry("minecraft:update_advancements", stateful()),
            entry("minecraft:select_advancements_tab", configurable()),
            entry("minecraft:update_recipes", stateful()),
            entry("minecraft:update_tags", stateful()),
            entry("minecraft:map_item_data", stateful()),
            entry("minecraft:player_look_at", viewerLocalEphemeral()),
            entry("minecraft:server_data", configurable()),
            entry("minecraft:set_chunk_cache_center", stateful()),
            entry("minecraft:set_chunk_cache_radius", stateful()),
            entry("minecraft:set_simulation_distance", stateful()),
            entry("minecraft:custom_payload", configurable()),
            entry("minecraft:delete_chat", configurable()),
            entry("minecraft:disguised_chat", configurable()),
            entry("minecraft:player_chat", configurable()),
            entry("minecraft:system_chat", configurable()),
            entry("minecraft:set_action_bar_text", viewerLocalConfigurable()),
            entry("minecraft:set_subtitle_text", configurable()),
            entry("minecraft:set_title_text", configurable()),
            entry("minecraft:set_titles_animation", configurable()),
            entry("minecraft:clear_titles", configurable()),
            entry("minecraft:open_book", configurable()),
            entry("minecraft:open_sign_editor", configurable()),
            entry("minecraft:server_links", configurable()),
            entry("minecraft:clear_dialog", configurable()),
            entry("minecraft:show_dialog", configurable()),
            entry("minecraft:animate", ephemeral()),
            entry("minecraft:add_transient_block", ephemeral()),
            entry("minecraft:post_effects", viewerLocalConfigurable()),
            entry("minecraft:award_stats", stateful()),
            entry("minecraft:block_destruction", ephemeral()),
            entry("minecraft:block_event", ephemeral()),
            entry("minecraft:damage_event", ephemeral()),
            entry("minecraft:entity_event", ephemeral()),
            entry("minecraft:explode", ephemeral()),
            entry("minecraft:game_event", ephemeral()),
            entry("minecraft:hurt_animation", ephemeral()),
            entry("minecraft:level_event", ephemeral()),
            entry("minecraft:level_particles", ephemeral()),
            entry("minecraft:sound", ephemeral()),
            entry("minecraft:sound_entity", ephemeral()),
            entry("minecraft:stop_sound", ephemeral()),
            entry("minecraft:swing_animation", ephemeral()),
            entry("minecraft:take_item_entity", ephemeral()),
            entry("minecraft:player_combat_end", ephemeral()),
            entry("minecraft:player_combat_enter", ephemeral()),
            entry("minecraft:player_combat_kill", ephemeral()),
            entry("minecraft:debug/block_value", unsupported()),
            entry("minecraft:debug/chunk_value", unsupported()),
            entry("minecraft:debug/entity_value", unsupported()),
            entry("minecraft:debug/event", unsupported()),
            entry("minecraft:debug_sample", unsupported()),
            entry("minecraft:game_test_highlight_pos", unsupported()),
            entry("minecraft:low_disk_space_warning", unsupported()),
            entry("minecraft:tag_query", unsupported()),
            entry("minecraft:test_instance_block_status", unsupported()));

    private static final PacketDefinitionCatalog<Paper26_3ReplayPacketRewriter> CATALOG =
            buildCatalog();

    private Paper26_3PacketCatalog() {}

    public static PacketDefinitionCatalog<Paper26_3ReplayPacketRewriter> catalog() {
        return CATALOG;
    }

    public static boolean explicitlyClassifies(String typeName) {
        return CATALOG.find(typeName).isPresent();
    }

    private static PacketDefinitionCatalog<Paper26_3ReplayPacketRewriter> buildCatalog() {
        var definitions = new ArrayList<PacketDefinition<Paper26_3ReplayPacketRewriter>>(
                CLASSIFICATIONS.size());
        CLASSIFICATIONS.forEach((identifier, raw) -> definitions.add(definition(identifier, raw)));
        return new PacketDefinitionCatalog<>(definitions);
    }

    private static PacketDefinition<Paper26_3ReplayPacketRewriter> definition(
            String identifier,
            RawClassification raw) {
        PacketClassification classification = raw.bind(scope(identifier));
        return switch (identifier) {
            case "minecraft:bundle_delimiter" -> new BundleDelimiterPacketDefinition(classification);
            case "minecraft:cookie_request" -> new CookieRequestPacketDefinition(classification);
            case "minecraft:disconnect" -> new DisconnectPacketDefinition(classification);
            case "minecraft:keep_alive" -> new KeepAlivePacketDefinition(classification);
            case "minecraft:ping" -> new PingPacketDefinition(classification);
            case "minecraft:pong_response" -> new PongResponsePacketDefinition(classification);
            case "minecraft:resource_pack_pop" -> new ResourcePackPopPacketDefinition(classification);
            case "minecraft:resource_pack_push" -> new ResourcePackPushPacketDefinition(classification);
            case "minecraft:start_configuration" -> new StartConfigurationPacketDefinition(classification);
            case "minecraft:store_cookie" -> new StoreCookiePacketDefinition(classification);
            case "minecraft:custom_report_details" -> new CustomReportDetailsPacketDefinition(classification);
            case "minecraft:chunk_batch_start" -> new ChunkBatchStartPacketDefinition(classification);
            case "minecraft:chunk_batch_finished" -> new ChunkBatchFinishedPacketDefinition(classification);
            case "minecraft:transfer" -> new TransferPacketDefinition(classification);
            case "minecraft:ticking_state" -> new TickingStatePacketDefinition(classification);
            case "minecraft:ticking_step" -> new TickingStepPacketDefinition(classification);
            case "minecraft:chunks_biomes" -> new ChunksBiomesPacketDefinition(classification);
            case "minecraft:level_chunk_with_light" -> new LevelChunkWithLightPacketDefinition(classification);
            case "minecraft:light_update" -> new LightUpdatePacketDefinition(classification);
            case "minecraft:block_entity_data" -> new BlockEntityDataPacketDefinition(classification);
            case "minecraft:block_update" -> new BlockUpdatePacketDefinition(classification);
            case "minecraft:forget_level_chunk" -> new ForgetLevelChunkPacketDefinition(classification);
            case "minecraft:section_blocks_update" -> new SectionBlocksUpdatePacketDefinition(classification);
            case "minecraft:block_changed_ack" -> new BlockChangedAckPacketDefinition(classification);
            case "minecraft:change_difficulty" -> new ChangeDifficultyPacketDefinition(classification);
            case "minecraft:game_rule_values" -> new GameRuleValuesPacketDefinition(classification);
            case "minecraft:initialize_border" -> new InitializeBorderPacketDefinition(classification);
            case "minecraft:set_border_center" -> new SetBorderCenterPacketDefinition(classification);
            case "minecraft:set_border_lerp_size" -> new SetBorderLerpSizePacketDefinition(classification);
            case "minecraft:set_border_size" -> new SetBorderSizePacketDefinition(classification);
            case "minecraft:set_border_warning_delay" -> new SetBorderWarningDelayPacketDefinition(classification);
            case "minecraft:set_border_warning_distance" -> new SetBorderWarningDistancePacketDefinition(classification);
            case "minecraft:set_default_spawn_position" -> new SetDefaultSpawnPositionPacketDefinition(classification);
            case "minecraft:set_time" -> new SetTimePacketDefinition(classification);
            case "minecraft:add_entity" -> new AddEntityPacketDefinition(classification);
            case "minecraft:entity_position_sync" -> new EntityPositionSyncPacketDefinition(classification);
            case "minecraft:move_entity_pos" -> new MoveEntityPosPacketDefinition(classification);
            case "minecraft:move_entity_pos_rot" -> new MoveEntityPosRotPacketDefinition(classification);
            case "minecraft:move_entity_rot" -> new MoveEntityRotPacketDefinition(classification);
            case "minecraft:move_minecart_along_track" -> new MoveMinecartAlongTrackPacketDefinition(classification);
            case "minecraft:move_vehicle" -> new MoveVehiclePacketDefinition(classification);
            case "minecraft:remove_entities" -> new RemoveEntitiesPacketDefinition(classification);
            case "minecraft:rotate_head" -> new RotateHeadPacketDefinition(classification);
            case "minecraft:set_camera" -> new SetCameraPacketDefinition(classification);
            case "minecraft:set_entity_data" -> new SetEntityDataPacketDefinition(classification);
            case "minecraft:set_entity_link" -> new SetEntityLinkPacketDefinition(classification);
            case "minecraft:set_entity_motion" -> new SetEntityMotionPacketDefinition(classification);
            case "minecraft:set_equipment" -> new SetEquipmentPacketDefinition(classification);
            case "minecraft:set_experience" -> new SetExperiencePacketDefinition(classification);
            case "minecraft:set_health" -> new SetHealthPacketDefinition(classification);
            case "minecraft:set_passengers" -> new SetPassengersPacketDefinition(classification);
            case "minecraft:teleport_entity" -> new TeleportEntityPacketDefinition(classification);
            case "minecraft:update_attributes" -> new UpdateAttributesPacketDefinition(classification);
            case "minecraft:update_mob_effect" -> new UpdateMobEffectPacketDefinition(classification);
            case "minecraft:remove_mob_effect" -> new RemoveMobEffectPacketDefinition(classification);
            case "minecraft:projectile_power" -> new ProjectilePowerPacketDefinition(classification);
            case "minecraft:waypoint" -> new WaypointPacketDefinition(classification);
            case "minecraft:player_abilities" -> new PlayerAbilitiesPacketDefinition(classification);
            case "minecraft:player_position" -> new PlayerPositionPacketDefinition(classification);
            case "minecraft:player_rotation" -> new PlayerRotationPacketDefinition(classification);
            case "minecraft:respawn" -> new RespawnPacketDefinition(classification);
            case "minecraft:login" -> new LoginPacketDefinition(classification);
            case "minecraft:container_close" -> new ContainerClosePacketDefinition(classification);
            case "minecraft:container_set_content" -> new ContainerSetContentPacketDefinition(classification);
            case "minecraft:container_set_data" -> new ContainerSetDataPacketDefinition(classification);
            case "minecraft:container_set_slot" -> new ContainerSetSlotPacketDefinition(classification);
            case "minecraft:cooldown" -> new CooldownPacketDefinition(classification);
            case "minecraft:command_suggestions" -> new CommandSuggestionsPacketDefinition(classification);
            case "minecraft:commands" -> new CommandsPacketDefinition(classification);
            case "minecraft:custom_chat_completions" -> new CustomChatCompletionsPacketDefinition(classification);
            case "minecraft:merchant_offers" -> new MerchantOffersPacketDefinition(classification);
            case "minecraft:mount_screen_open" -> new MountScreenOpenPacketDefinition(classification);
            case "minecraft:open_screen" -> new OpenScreenPacketDefinition(classification);
            case "minecraft:place_ghost_recipe" -> new PlaceGhostRecipePacketDefinition(classification);
            case "minecraft:recipe_book_add" -> new RecipeBookAddPacketDefinition(classification);
            case "minecraft:recipe_book_remove" -> new RecipeBookRemovePacketDefinition(classification);
            case "minecraft:recipe_book_settings" -> new RecipeBookSettingsPacketDefinition(classification);
            case "minecraft:set_cursor_item" -> new SetCursorItemPacketDefinition(classification);
            case "minecraft:set_held_slot" -> new SetHeldSlotPacketDefinition(classification);
            case "minecraft:set_player_inventory" -> new SetPlayerInventoryPacketDefinition(classification);
            case "minecraft:player_info_remove" -> new PlayerInfoRemovePacketDefinition(classification);
            case "minecraft:player_info_update" -> new PlayerInfoUpdatePacketDefinition(classification);
            case "minecraft:set_objective" -> new SetObjectivePacketDefinition(classification);
            case "minecraft:set_display_objective" -> new SetDisplayObjectivePacketDefinition(classification);
            case "minecraft:set_player_team" -> new SetPlayerTeamPacketDefinition(classification);
            case "minecraft:set_score" -> new SetScorePacketDefinition(classification);
            case "minecraft:reset_score" -> new ResetScorePacketDefinition(classification);
            case "minecraft:boss_event" -> new BossEventPacketDefinition(classification);
            case "minecraft:tab_list" -> new TabListPacketDefinition(classification);
            case "minecraft:update_advancements" -> new UpdateAdvancementsPacketDefinition(classification);
            case "minecraft:select_advancements_tab" -> new SelectAdvancementsTabPacketDefinition(classification);
            case "minecraft:update_recipes" -> new UpdateRecipesPacketDefinition(classification);
            case "minecraft:update_tags" -> new UpdateTagsPacketDefinition(classification);
            case "minecraft:map_item_data" -> new MapItemDataPacketDefinition(classification);
            case "minecraft:player_look_at" -> new PlayerLookAtPacketDefinition(classification);
            case "minecraft:server_data" -> new ServerDataPacketDefinition(classification);
            case "minecraft:set_chunk_cache_center" -> new SetChunkCacheCenterPacketDefinition(classification);
            case "minecraft:set_chunk_cache_radius" -> new SetChunkCacheRadiusPacketDefinition(classification);
            case "minecraft:set_simulation_distance" -> new SetSimulationDistancePacketDefinition(classification);
            case "minecraft:custom_payload" -> new CustomPayloadPacketDefinition(classification);
            case "minecraft:delete_chat" -> new DeleteChatPacketDefinition(classification);
            case "minecraft:disguised_chat" -> new DisguisedChatPacketDefinition(classification);
            case "minecraft:player_chat" -> new PlayerChatPacketDefinition(classification);
            case "minecraft:system_chat" -> new SystemChatPacketDefinition(classification);
            case "minecraft:set_action_bar_text" -> new SetActionBarTextPacketDefinition(classification);
            case "minecraft:set_subtitle_text" -> new SetSubtitleTextPacketDefinition(classification);
            case "minecraft:set_title_text" -> new SetTitleTextPacketDefinition(classification);
            case "minecraft:set_titles_animation" -> new SetTitlesAnimationPacketDefinition(classification);
            case "minecraft:clear_titles" -> new ClearTitlesPacketDefinition(classification);
            case "minecraft:open_book" -> new OpenBookPacketDefinition(classification);
            case "minecraft:open_sign_editor" -> new OpenSignEditorPacketDefinition(classification);
            case "minecraft:server_links" -> new ServerLinksPacketDefinition(classification);
            case "minecraft:clear_dialog" -> new ClearDialogPacketDefinition(classification);
            case "minecraft:show_dialog" -> new ShowDialogPacketDefinition(classification);
            case "minecraft:animate" -> new AnimatePacketDefinition(classification);
            case "minecraft:add_transient_block" -> new AddTransientBlockPacketDefinition(classification);
            case "minecraft:post_effects" -> new PostEffectsPacketDefinition(classification);
            case "minecraft:award_stats" -> new AwardStatsPacketDefinition(classification);
            case "minecraft:block_destruction" -> new BlockDestructionPacketDefinition(classification);
            case "minecraft:block_event" -> new BlockEventPacketDefinition(classification);
            case "minecraft:damage_event" -> new DamageEventPacketDefinition(classification);
            case "minecraft:entity_event" -> new EntityEventPacketDefinition(classification);
            case "minecraft:explode" -> new ExplodePacketDefinition(classification);
            case "minecraft:game_event" -> new GameEventPacketDefinition(classification);
            case "minecraft:hurt_animation" -> new HurtAnimationPacketDefinition(classification);
            case "minecraft:level_event" -> new LevelEventPacketDefinition(classification);
            case "minecraft:level_particles" -> new LevelParticlesPacketDefinition(classification);
            case "minecraft:sound" -> new SoundPacketDefinition(classification);
            case "minecraft:sound_entity" -> new SoundEntityPacketDefinition(classification);
            case "minecraft:stop_sound" -> new StopSoundPacketDefinition(classification);
            case "minecraft:swing_animation" -> new SwingAnimationPacketDefinition(classification);
            case "minecraft:take_item_entity" -> new TakeItemEntityPacketDefinition(classification);
            case "minecraft:player_combat_end" -> new PlayerCombatEndPacketDefinition(classification);
            case "minecraft:player_combat_enter" -> new PlayerCombatEnterPacketDefinition(classification);
            case "minecraft:player_combat_kill" -> new PlayerCombatKillPacketDefinition(classification);
            case "minecraft:debug/block_value" -> new DebugBlockValuePacketDefinition(classification);
            case "minecraft:debug/chunk_value" -> new DebugChunkValuePacketDefinition(classification);
            case "minecraft:debug/entity_value" -> new DebugEntityValuePacketDefinition(classification);
            case "minecraft:debug/event" -> new DebugEventPacketDefinition(classification);
            case "minecraft:debug_sample" -> new DebugSamplePacketDefinition(classification);
            case "minecraft:game_test_highlight_pos" -> new GameTestHighlightPosPacketDefinition(classification);
            case "minecraft:low_disk_space_warning" -> new LowDiskSpaceWarningPacketDefinition(classification);
            case "minecraft:tag_query" -> new TagQueryPacketDefinition(classification);
            case "minecraft:test_instance_block_status" -> new TestInstanceBlockStatusPacketDefinition(classification);
            default -> throw new IllegalArgumentException(
                    "missing packet implementation for " + identifier);
        };
    }

    private static PacketDescriptor.PlaybackScope scope(String identifier) {
        if (NeutralObserverPolicy.isViewerLocal(identifier)) {
            return PacketDescriptor.PlaybackScope.VIEWER_LOCAL;
        }
        if (IDENTITY_BEARING.contains(identifier)) {
            return PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES;
        }
        RawClassification raw = CLASSIFICATIONS.get(identifier);
        if (raw != null && (raw.disposition() == PacketDisposition.CONTROL
                || raw.disposition() == PacketDisposition.UNSUPPORTED)) {
            return PacketDescriptor.PlaybackScope.UNSAFE;
        }
        return PacketDescriptor.PlaybackScope.SAFE;
    }

    private static Map.Entry<String, RawClassification> entry(
            String identifier,
            RawClassification classification) {
        return Map.entry(identifier, classification);
    }

    private static RawClassification stateful() {
        return new RawClassification(PacketDisposition.STATEFUL, true, true, true);
    }

    private static RawClassification viewerLocalStateful() {
        return new RawClassification(PacketDisposition.STATEFUL, true, true, false);
    }

    private static RawClassification ephemeral() {
        return new RawClassification(PacketDisposition.EPHEMERAL, true, true, false);
    }

    private static RawClassification viewerLocalEphemeral() {
        return ephemeral();
    }

    private static RawClassification configurable() {
        return new RawClassification(PacketDisposition.CONFIGURABLE, true, true, false);
    }

    private static RawClassification viewerLocalConfigurable() {
        return configurable();
    }

    private static RawClassification control() {
        return new RawClassification(PacketDisposition.CONTROL, true, false, false);
    }

    private static RawClassification unsupported() {
        return new RawClassification(PacketDisposition.UNSUPPORTED, true, false, false);
    }

    private record RawClassification(
            PacketDisposition disposition,
            boolean captureByDefault,
            boolean replayable,
            boolean checkpointRelevant) {

        private PacketClassification bind(PacketDescriptor.PlaybackScope scope) {
            return new PacketClassification(
                    disposition,
                    captureByDefault,
                    replayable,
                    checkpointRelevant && scope != PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                    scope);
        }
    }
}
