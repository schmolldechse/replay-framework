package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.scoreboard;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the scoreboard protocol domain. */
public final class ScoreboardPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id080ResetScorePacketDefinition(),
                    new Id100SetDisplayObjectivePacketDefinition(),
                    new Id108SetObjectivePacketDefinition(),
                    new Id111SetPlayerTeamPacketDefinition(),
                    new Id112SetScorePacketDefinition());

    private ScoreboardPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
