package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.ui;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.3 serverbound definitions owned by the ui protocol domain. */
public final class UiServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id051SeenAdvancementsPacketDefinition());

    private UiServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
