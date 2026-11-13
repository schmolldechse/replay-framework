package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.ui;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the user-interface protocol domain. */
public final class UiPacketDefinitions {

    private static final List<PacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id014ClearTitlesPacketDefinition(),
                    new Id015CommandSuggestionsPacketDefinition(),
                    new Id016CommandsPacketDefinition(),
                    new Id017ContainerClosePacketDefinition(),
                    new Id018ContainerSetContentPacketDefinition(),
                    new Id019ContainerSetDataPacketDefinition(),
                    new Id020ContainerSetSlotPacketDefinition(),
                    new Id022CooldownPacketDefinition());

    private UiPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
