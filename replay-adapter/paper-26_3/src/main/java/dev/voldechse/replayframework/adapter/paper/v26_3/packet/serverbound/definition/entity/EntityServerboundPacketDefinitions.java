package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.entity;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.3 serverbound definitions owned by the entity protocol domain. */
public final class EntityServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id001AttackPacketDefinition(),
                    new Id026InteractPacketDefinition(),
                    new Id046PunchPacketDefinition(),
                    new Id067UseItemPacketDefinition());

    private EntityServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
