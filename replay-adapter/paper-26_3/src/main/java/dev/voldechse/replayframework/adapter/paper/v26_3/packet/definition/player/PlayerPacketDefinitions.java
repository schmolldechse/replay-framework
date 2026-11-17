package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.player;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the player protocol domain. */
public final class PlayerPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(new Id003AwardStatsPacketDefinition());

    private PlayerPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
