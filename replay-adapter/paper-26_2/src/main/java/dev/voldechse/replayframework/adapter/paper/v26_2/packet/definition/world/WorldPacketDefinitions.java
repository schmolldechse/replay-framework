package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the world protocol domain. */
public final class WorldPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id004BlockChangedAckPacketDefinition(),
                    new Id006BlockEntityDataPacketDefinition(),
                    new Id007BlockEventPacketDefinition(),
                    new Id008BlockUpdatePacketDefinition(),
                    new Id010ChangeDifficultyPacketDefinition(),
                    new Id013ChunksBiomesPacketDefinition(),
                    new Id037ForgetLevelChunkPacketDefinition(),
                    new Id038GameEventPacketDefinition(),
                    new Id039GameRuleValuesPacketDefinition(),
                    new Id043InitializeBorderPacketDefinition(),
                    new Id045LevelChunkWithLightPacketDefinition(),
                    new Id046LevelEventPacketDefinition(),
                    new Id048LightUpdatePacketDefinition(),
                    new Id051MapItemDataPacketDefinition(),
                    new Id084SectionBlocksUpdatePacketDefinition(),
                    new Id088SetBorderCenterPacketDefinition(),
                    new Id089SetBorderLerpSizePacketDefinition());

    private WorldPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
