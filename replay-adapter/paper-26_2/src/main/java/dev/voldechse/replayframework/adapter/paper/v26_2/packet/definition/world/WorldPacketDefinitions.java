package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the world protocol domain. */
public final class WorldPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(new Id004BlockChangedAckPacketDefinition());

    private WorldPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
