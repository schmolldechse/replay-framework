package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.world;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.2 serverbound definitions owned by the world protocol domain. */
public final class WorldServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id041PlayerActionPacketDefinition(),
                    new Id057SetGameRulePacketDefinition(),
                    new Id061SignUpdatePacketDefinition(),
                    new Id066UseItemOnPacketDefinition());

    private WorldServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
