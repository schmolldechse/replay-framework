package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.2 definitions owned by the user-interface protocol domain. */
public final class UiPacketDefinitions {

    private static final List<PacketDefinition<Paper26_2ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id014ClearTitlesPacketDefinition(),
                    new Id015CommandSuggestionsPacketDefinition(),
                    new Id016CommandsPacketDefinition(),
                    new Id017ContainerClosePacketDefinition(),
                    new Id018ContainerSetContentPacketDefinition(),
                    new Id019ContainerSetDataPacketDefinition(),
                    new Id020ContainerSetSlotPacketDefinition(),
                    new Id022CooldownPacketDefinition(),
                    new Id041MountScreenOpenPacketDefinition(),
                    new Id050LowDiskSpaceWarningPacketDefinition(),
                    new Id052MerchantOffersPacketDefinition(),
                    new Id058OpenBookPacketDefinition(),
                    new Id059OpenScreenPacketDefinition(),
                    new Id060OpenSignEditorPacketDefinition(),
                    new Id063PlaceGhostRecipePacketDefinition(),
                    new Id074RecipeBookAddPacketDefinition(),
                    new Id075RecipeBookRemovePacketDefinition(),
                    new Id076RecipeBookSettingsPacketDefinition(),
                    new Id085SelectAdvancementsTabPacketDefinition());

    private UiPacketDefinitions() {}

    public static List<PacketDefinition<Paper26_2ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
