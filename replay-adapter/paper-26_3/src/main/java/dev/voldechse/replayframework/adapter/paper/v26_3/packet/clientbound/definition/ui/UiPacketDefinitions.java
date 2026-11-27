package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;

/** Immutable Paper 26.3 definitions owned by the user-interface protocol domain. */
public final class UiPacketDefinitions {

    private static final List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> DEFINITIONS =
            List.of(
                    new Id014ClearTitlesPacketDefinition(),
                    new Id015CommandSuggestionsPacketDefinition(),
                    new Id016CommandsPacketDefinition(),
                    new Id017ContainerClosePacketDefinition(),
                    new Id018ContainerSetContentPacketDefinition(),
                    new Id019ContainerSetDataPacketDefinition(),
                    new Id020ContainerSetSlotPacketDefinition(),
                    new Id022CooldownPacketDefinition(),
                    new Id042MountScreenOpenPacketDefinition(),
                    new Id051LowDiskSpaceWarningPacketDefinition(),
                    new Id053MerchantOffersPacketDefinition(),
                    new Id059OpenBookPacketDefinition(),
                    new Id060OpenScreenPacketDefinition(),
                    new Id061OpenSignEditorPacketDefinition(),
                    new Id064PlaceGhostRecipePacketDefinition(),
                    new Id075RecipeBookAddPacketDefinition(),
                    new Id076RecipeBookRemovePacketDefinition(),
                    new Id077RecipeBookSettingsPacketDefinition(),
                    new Id087SelectAdvancementsTabPacketDefinition(),
                    new Id088ServerDataPacketDefinition(),
                    new Id089SetActionBarTextPacketDefinition(),
                    new Id098SetCursorItemPacketDefinition(),
                    new Id110SetPlayerInventoryPacketDefinition(),
                    new Id114SetSubtitleTextPacketDefinition(),
                    new Id116SetTitleTextPacketDefinition(),
                    new Id117SetTitlesAnimationPacketDefinition(),
                    new Id125TabListPacketDefinition(),
                    new Id136UpdateRecipesPacketDefinition(),
                    new Id140ServerLinksPacketDefinition(),
                    new Id141WaypointPacketDefinition(),
                    new Id142ClearDialogPacketDefinition(),
                    new Id143ShowDialogPacketDefinition());

    private UiPacketDefinitions() {}

    public static List<ClientboundPacketDefinition<Paper26_3ReplayPacketRewriter>> definitions() {
        return DEFINITIONS;
    }
}
