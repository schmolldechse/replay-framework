package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.scoreboard;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the scoreboard protocol domain. */
public final class ScoreboardPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id079ResetScorePacketDefinition(),
                    new Id098SetDisplayObjectivePacketDefinition(),
                    new Id106SetObjectivePacketDefinition(),
                    new Id109SetPlayerTeamPacketDefinition(),
                    new Id110SetScorePacketDefinition());

    private ScoreboardPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
