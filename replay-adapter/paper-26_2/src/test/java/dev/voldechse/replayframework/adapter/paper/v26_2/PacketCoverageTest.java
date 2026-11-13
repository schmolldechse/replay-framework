package dev.voldechse.replayframework.adapter.paper.v26_2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import org.junit.jupiter.api.Test;

/** Verifies explicit coverage while packet implementations are added incrementally. */
class PacketCoverageTest {

    @Test
    void classifiesOnlyImplementedPacketsExplicitly() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        assertEquals(
                java.util.List.of(
                        "play.clientbound.minecraft:bundle_delimiter",
                        "play.clientbound.minecraft:add_entity",
                        "play.clientbound.minecraft:animate",
                        "play.clientbound.minecraft:award_stats",
                        "play.clientbound.minecraft:block_changed_ack",
                        "play.clientbound.minecraft:block_destruction",
                        "play.clientbound.minecraft:block_entity_data",
                        "play.clientbound.minecraft:block_event",
                        "play.clientbound.minecraft:block_update",
                        "play.clientbound.minecraft:boss_event",
                        "play.clientbound.minecraft:change_difficulty",
                        "play.clientbound.minecraft:chunk_batch_finished",
                        "play.clientbound.minecraft:chunk_batch_start",
                        "play.clientbound.minecraft:chunks_biomes",
                        "play.clientbound.minecraft:clear_titles",
                        "play.clientbound.minecraft:command_suggestions",
                        "play.clientbound.minecraft:commands",
                        "play.clientbound.minecraft:container_close",
                        "play.clientbound.minecraft:container_set_content",
                        "play.clientbound.minecraft:container_set_data",
                        "play.clientbound.minecraft:container_set_slot",
                        "play.clientbound.minecraft:cookie_request",
                        "play.clientbound.minecraft:cooldown",
                        "play.clientbound.minecraft:custom_chat_completions",
                        "play.clientbound.minecraft:custom_payload",
                        "play.clientbound.minecraft:damage_event",
                        "play.clientbound.minecraft:debug/block_value",
                        "play.clientbound.minecraft:debug/chunk_value",
                        "play.clientbound.minecraft:debug/entity_value",
                        "play.clientbound.minecraft:debug/event",
                        "play.clientbound.minecraft:debug_sample",
                        "play.clientbound.minecraft:delete_chat",
                        "play.clientbound.minecraft:disconnect",
                        "play.clientbound.minecraft:disguised_chat",
                        "play.clientbound.minecraft:entity_event",
                        "play.clientbound.minecraft:entity_position_sync",
                        "play.clientbound.minecraft:explode",
                        "play.clientbound.minecraft:forget_level_chunk",
                        "play.clientbound.minecraft:game_event",
                        "play.clientbound.minecraft:game_rule_values",
                        "play.clientbound.minecraft:game_test_highlight_pos",
                        "play.clientbound.minecraft:mount_screen_open",
                        "play.clientbound.minecraft:hurt_animation",
                        "play.clientbound.minecraft:initialize_border",
                        "play.clientbound.minecraft:keep_alive",
                        "play.clientbound.minecraft:level_chunk_with_light",
                        "play.clientbound.minecraft:level_event",
                        "play.clientbound.minecraft:level_particles",
                        "play.clientbound.minecraft:light_update",
                        "play.clientbound.minecraft:login",
                        "play.clientbound.minecraft:low_disk_space_warning",
                        "play.clientbound.minecraft:map_item_data",
                        "play.clientbound.minecraft:merchant_offers",
                        "play.clientbound.minecraft:move_entity_pos",
                        "play.clientbound.minecraft:move_entity_pos_rot",
                        "play.clientbound.minecraft:move_minecart_along_track",
                        "play.clientbound.minecraft:move_entity_rot",
                        "play.clientbound.minecraft:move_vehicle",
                        "play.clientbound.minecraft:open_book",
                        "play.clientbound.minecraft:open_screen",
                        "play.clientbound.minecraft:open_sign_editor",
                        "play.clientbound.minecraft:ping",
                        "play.clientbound.minecraft:pong_response",
                        "play.clientbound.minecraft:place_ghost_recipe",
                        "play.clientbound.minecraft:player_abilities",
                        "play.clientbound.minecraft:player_chat",
                        "play.clientbound.minecraft:player_combat_end"),
                Paper26_2PacketCatalog.catalog().definitions().stream()
                        .map(definition -> definition.typeName())
                        .toList());
        var registry = Paper26_2PacketRegistry.discover();
        for (var descriptor : registry.descriptors()) {
            if (descriptor.phase() == PacketPhase.PLAY) {
                boolean bundleDelimiter =
                        descriptor.typeName().equals("play.clientbound.minecraft:bundle_delimiter");
                boolean addEntity =
                        descriptor.typeName().equals("play.clientbound.minecraft:add_entity");
                boolean animate =
                        descriptor.typeName().equals("play.clientbound.minecraft:animate");
                boolean awardStats =
                        descriptor.typeName().equals("play.clientbound.minecraft:award_stats");
                boolean blockChangedAck =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_changed_ack");
                boolean blockDestruction =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_destruction");
                boolean blockEntityData =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_entity_data");
                boolean blockEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_event");
                boolean blockUpdate =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_update");
                boolean bossEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:boss_event");
                boolean changeDifficulty =
                        descriptor.typeName().equals("play.clientbound.minecraft:change_difficulty");
                boolean chunkBatchFinished = descriptor.typeName()
                        .equals("play.clientbound.minecraft:chunk_batch_finished");
                boolean chunkBatchStart =
                        descriptor.typeName().equals("play.clientbound.minecraft:chunk_batch_start");
                boolean chunksBiomes =
                        descriptor.typeName().equals("play.clientbound.minecraft:chunks_biomes");
                boolean clearTitles =
                        descriptor.typeName().equals("play.clientbound.minecraft:clear_titles");
                boolean commandSuggestions = descriptor.typeName()
                        .equals("play.clientbound.minecraft:command_suggestions");
                boolean commands =
                        descriptor.typeName().equals("play.clientbound.minecraft:commands");
                boolean containerPacket = java.util.Set.of(
                                "play.clientbound.minecraft:container_close",
                                "play.clientbound.minecraft:container_set_content",
                                "play.clientbound.minecraft:container_set_data",
                                "play.clientbound.minecraft:container_set_slot")
                        .contains(descriptor.typeName());
                boolean cookieRequest =
                        descriptor.typeName().equals("play.clientbound.minecraft:cookie_request");
                boolean cooldown =
                        descriptor.typeName().equals("play.clientbound.minecraft:cooldown");
                boolean customChatCompletions = descriptor.typeName()
                        .equals("play.clientbound.minecraft:custom_chat_completions");
                boolean customPayload =
                        descriptor.typeName().equals("play.clientbound.minecraft:custom_payload");
                boolean damageEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:damage_event");
                boolean debugBlockValue = descriptor.typeName()
                        .equals("play.clientbound.minecraft:debug/block_value");
                boolean debugChunkValue = descriptor.typeName()
                        .equals("play.clientbound.minecraft:debug/chunk_value");
                boolean debugEntityValue = descriptor.typeName()
                        .equals("play.clientbound.minecraft:debug/entity_value");
                boolean debugEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:debug/event");
                boolean debugSample =
                        descriptor.typeName().equals("play.clientbound.minecraft:debug_sample");
                boolean deleteChat =
                        descriptor.typeName().equals("play.clientbound.minecraft:delete_chat");
                boolean disconnect =
                        descriptor.typeName().equals("play.clientbound.minecraft:disconnect");
                boolean disguisedChat =
                        descriptor.typeName().equals("play.clientbound.minecraft:disguised_chat");
                boolean entityEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:entity_event");
                boolean entityPositionSync = descriptor.typeName()
                        .equals("play.clientbound.minecraft:entity_position_sync");
                boolean explode =
                        descriptor.typeName().equals("play.clientbound.minecraft:explode");
                boolean forgetLevelChunk = descriptor.typeName()
                        .equals("play.clientbound.minecraft:forget_level_chunk");
                boolean gameEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:game_event");
                boolean gameRuleValues = descriptor.typeName()
                        .equals("play.clientbound.minecraft:game_rule_values");
                boolean gameTestHighlightPos = descriptor.typeName()
                        .equals("play.clientbound.minecraft:game_test_highlight_pos");
                boolean hurtAnimation =
                        descriptor.typeName().equals("play.clientbound.minecraft:hurt_animation");
                boolean mountScreenOpen = descriptor.typeName()
                        .equals("play.clientbound.minecraft:mount_screen_open");
                boolean initializeBorder = descriptor.typeName()
                        .equals("play.clientbound.minecraft:initialize_border");
                boolean keepAlive = descriptor.typeName()
                        .equals("play.clientbound.minecraft:keep_alive");
                boolean levelChunkWithLight = descriptor.typeName()
                        .equals("play.clientbound.minecraft:level_chunk_with_light");
                boolean levelEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:level_event");
                boolean levelParticles = descriptor.typeName()
                        .equals("play.clientbound.minecraft:level_particles");
                boolean lightUpdate =
                        descriptor.typeName().equals("play.clientbound.minecraft:light_update");
                boolean login = descriptor.typeName().equals("play.clientbound.minecraft:login");
                boolean lowDiskSpaceWarning = descriptor.typeName()
                        .equals("play.clientbound.minecraft:low_disk_space_warning");
                boolean mapItemData = descriptor.typeName()
                        .equals("play.clientbound.minecraft:map_item_data");
                boolean merchantOffers = descriptor.typeName()
                        .equals("play.clientbound.minecraft:merchant_offers");
                boolean moveEntityPos = descriptor.typeName()
                        .equals("play.clientbound.minecraft:move_entity_pos");
                boolean moveEntityPosRot = descriptor.typeName()
                        .equals("play.clientbound.minecraft:move_entity_pos_rot");
                boolean moveMinecartAlongTrack = descriptor.typeName()
                        .equals("play.clientbound.minecraft:move_minecart_along_track");
                boolean moveEntityRot = descriptor.typeName()
                        .equals("play.clientbound.minecraft:move_entity_rot");
                boolean moveVehicle = descriptor.typeName()
                        .equals("play.clientbound.minecraft:move_vehicle");
                boolean openBook = descriptor.typeName()
                        .equals("play.clientbound.minecraft:open_book");
                boolean openScreen = descriptor.typeName()
                        .equals("play.clientbound.minecraft:open_screen");
                boolean openSignEditor = descriptor.typeName()
                        .equals("play.clientbound.minecraft:open_sign_editor");
                boolean ping = descriptor.typeName()
                        .equals("play.clientbound.minecraft:ping");
                boolean pongResponse = descriptor.typeName()
                        .equals("play.clientbound.minecraft:pong_response");
                boolean placeGhostRecipe = descriptor.typeName()
                        .equals("play.clientbound.minecraft:place_ghost_recipe");
                boolean playerAbilities = descriptor.typeName()
                        .equals("play.clientbound.minecraft:player_abilities");
                boolean playerChat = descriptor.typeName()
                        .equals("play.clientbound.minecraft:player_chat");
                boolean playerCombatEnd = descriptor.typeName()
                        .equals("play.clientbound.minecraft:player_combat_end");
                PacketDisposition expectedDisposition;
                if (bundleDelimiter
                        || blockChangedAck
                        || chunkBatchFinished
                        || chunkBatchStart
                        || cookieRequest
                        || debugBlockValue
                        || debugChunkValue
                        || debugEntityValue
                        || debugEvent
                        || debugSample
                        || gameTestHighlightPos
                        || keepAlive
                        || ping
                        || pongResponse
                        || login
                        || disconnect) {
                    expectedDisposition = PacketDisposition.CONTROL;
                } else if (addEntity
                        || blockEntityData
                        || blockUpdate
                        || bossEvent
                        || changeDifficulty
                        || chunksBiomes
                        || clearTitles
                        || commands
                        || containerPacket
                        || cooldown
                        || customChatCompletions
                        || entityPositionSync
                        || moveMinecartAlongTrack
                        || forgetLevelChunk
                        || gameRuleValues
                        || mountScreenOpen
                        || initializeBorder
                        || levelChunkWithLight
                        || lightUpdate
                        || mapItemData
                        || merchantOffers) {
                    expectedDisposition = PacketDisposition.STATEFUL;
                } else if (animate
                        || blockDestruction
                        || blockEvent
                        || commandSuggestions
                        || damageEvent
                        || deleteChat
                        || disguisedChat
                        || entityEvent
                        || moveEntityPos
                        || moveEntityPosRot
                        || moveEntityRot
                        || moveVehicle
                        || openBook
                        || openScreen
                        || openSignEditor
                        || playerCombatEnd
                        || placeGhostRecipe
                        || explode
                        || hurtAnimation
                        || gameEvent
                        || levelEvent
                        || levelParticles
                        || lowDiskSpaceWarning) {
                    expectedDisposition = PacketDisposition.EPHEMERAL;
                } else if (customPayload || playerChat) {
                    expectedDisposition = PacketDisposition.CONFIGURABLE;
                } else if (awardStats || playerAbilities) {
                    expectedDisposition = PacketDisposition.STATEFUL;
                } else {
                    expectedDisposition = PacketDisposition.UNSUPPORTED;
                }
                assertEquals(expectedDisposition, descriptor.disposition());
                assertTrue(descriptor.captureByDefault());
                assertEquals(
                        addEntity
                                || animate
                                || awardStats
                                || blockDestruction
                                || blockEntityData
                                || blockEvent
                                || blockUpdate
                                || bossEvent
                                || changeDifficulty
                                || chunksBiomes
                                || clearTitles
                                || commandSuggestions
                                || commands
                                || containerPacket
                                || cooldown
                                || customChatCompletions
                                || customPayload
                                || lowDiskSpaceWarning
                                || mapItemData
                                || merchantOffers
                                || placeGhostRecipe
                                || moveEntityPos
                                || moveEntityPosRot
                                || moveEntityRot
                                || moveVehicle
                                || openBook
                                || openScreen
                                || openSignEditor
                                || playerAbilities
                                || playerChat
                                || playerCombatEnd
                                || moveMinecartAlongTrack
                                || damageEvent
                                || deleteChat
                                || disguisedChat
                                || entityEvent
                                || entityPositionSync
                                || explode
                                || forgetLevelChunk
                                || gameEvent
                                || gameRuleValues
                                || hurtAnimation
                                || mountScreenOpen
                                || initializeBorder
                                || levelChunkWithLight
                                || levelEvent
                                || levelParticles
                                || lightUpdate,
                        descriptor.replayable());
                assertEquals(
                        addEntity
                                || blockEntityData
                                || blockUpdate
                                || bossEvent
                                || chunksBiomes
                                || moveMinecartAlongTrack
                                || entityPositionSync
                                || forgetLevelChunk
                                || initializeBorder
                                || levelChunkWithLight
                                || lightUpdate,
                        descriptor.checkpointRelevant());
                if (addEntity
                        || animate
                        || blockDestruction
                        || bossEvent
                        || damageEvent
                        || entityEvent
                        || moveEntityPos
                        || moveEntityPosRot
                        || moveEntityRot
                        || moveMinecartAlongTrack
                        || entityPositionSync
                        || explode
                        || hurtAnimation
                        || levelParticles) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                            descriptor.playbackScope());
                } else if (awardStats
                        || changeDifficulty
                        || clearTitles
                        || commandSuggestions
                        || commands
                        || containerPacket
                        || cooldown
                        || customChatCompletions
                        || customPayload
                        || lowDiskSpaceWarning
                        || placeGhostRecipe
                        || playerAbilities
                        || moveVehicle
                        || openBook
                        || openScreen
                        || openSignEditor
                        || playerCombatEnd
                        || mapItemData
                        || merchantOffers
                        || deleteChat
                        || disguisedChat
                        || gameRuleValues
                        || mountScreenOpen) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                            descriptor.playbackScope());
                } else if (blockEntityData
                        || blockEvent
                        || blockUpdate
                        || chunksBiomes
                        || forgetLevelChunk
                        || gameEvent
                        || levelChunkWithLight
                        || levelEvent
                        || lightUpdate
                        || initializeBorder
                        || playerChat) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.SAFE,
                            descriptor.playbackScope());
                } else {
                    assertFalse(descriptor.replayable());
                    assertFalse(descriptor.checkpointRelevant());
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.UNSAFE,
                            descriptor.playbackScope());
                }
                assertEquals(
                        bundleDelimiter
                                || addEntity
                                || animate
                                || awardStats
                                || blockChangedAck
                                || blockDestruction
                                || blockEntityData
                                || blockEvent
                                || blockUpdate
                                || bossEvent
                                || changeDifficulty
                                || chunkBatchFinished
                                || chunkBatchStart
                                || chunksBiomes
                                || clearTitles
                                || commandSuggestions
                                || commands
                                || containerPacket
                                || cookieRequest
                                || cooldown
                                || customChatCompletions
                                || customPayload
                                || playerChat
                                || damageEvent
                                || debugBlockValue
                                || debugChunkValue
                                || debugEntityValue
                                || debugEvent
                                || debugSample
                                || deleteChat
                                || disguisedChat
                                || entityEvent
                                || entityPositionSync
                                || explode
                                || forgetLevelChunk
                                || gameEvent
                                || gameRuleValues
                                || gameTestHighlightPos
                                || hurtAnimation
                                || mountScreenOpen
                                || initializeBorder
                                || keepAlive
                                || levelChunkWithLight
                                || levelEvent
                                || levelParticles
                                || lightUpdate
                                || login
                                || lowDiskSpaceWarning
                                || mapItemData
                                || merchantOffers
                                || moveEntityPos
                                || moveEntityPosRot
                                || moveEntityRot
                                || moveMinecartAlongTrack
                                || moveVehicle
                                || openBook
                                || openScreen
                                || openSignEditor
                                || ping
                                || pongResponse
                                || placeGhostRecipe
                                || playerAbilities
                                || playerCombatEnd
                                || disconnect,
                        Paper26_2PacketRegistry.explicitlyClassifies(descriptor.typeName()));
            }
        }
    }
}
