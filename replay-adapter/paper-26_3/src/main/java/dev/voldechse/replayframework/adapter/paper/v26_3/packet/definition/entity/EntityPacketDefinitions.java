package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the entity protocol domain. */
public final class EntityPacketDefinitions {

    private static final List<PacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(new Id001AddEntityPacketDefinition());

    private EntityPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
