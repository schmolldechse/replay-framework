package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the world protocol domain. */
public final class WorldPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id004BlockChangedAckPacketDefinition(),
                    new Id006BlockEntityDataPacketDefinition(),
                    new Id007BlockEventPacketDefinition(),
                    new Id008BlockUpdatePacketDefinition(),
                    new Id037AddTransientBlockPacketDefinition(),
                    new Id010ChangeDifficultyPacketDefinition(),
                    new Id013ChunksBiomesPacketDefinition(),
                    new Id038ForgetLevelChunkPacketDefinition(),
                    new Id039GameEventPacketDefinition(),
                    new Id040GameRuleValuesPacketDefinition(),
                    new Id044InitializeBorderPacketDefinition(),
                    new Id046LevelChunkWithLightPacketDefinition(),
                    new Id047LevelEventPacketDefinition(),
                    new Id049LightUpdatePacketDefinition(),
                    new Id052MapItemDataPacketDefinition(),
                    new Id086SectionBlocksUpdatePacketDefinition(),
                    new Id090SetBorderCenterPacketDefinition(),
                    new Id091SetBorderLerpSizePacketDefinition(),
                    new Id092SetBorderSizePacketDefinition(),
                    new Id093SetBorderWarningDelayPacketDefinition(),
                    new Id094SetBorderWarningDistancePacketDefinition(),
                    new Id096SetChunkCacheCenterPacketDefinition(),
                    new Id097SetChunkCacheRadiusPacketDefinition(),
                    new Id099SetDefaultSpawnPositionPacketDefinition(),
                    new Id113SetSimulationDistancePacketDefinition(),
                    new Id115SetTimePacketDefinition(),
                    new Id137UpdateTagsPacketDefinition());

    private WorldPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
