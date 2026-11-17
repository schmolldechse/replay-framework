package dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.2 serverbound definitions owned by the inventory protocol domain. */
public final class InventoryServerboundPacketDefinitions {

    private static final List<ServerboundPacketDefinition> DEFINITIONS =
            List.of(
                    new Id003BundleItemSelectedPacketDefinition(),
                    new Id017ContainerButtonClickPacketDefinition(),
                    new Id018ContainerClickPacketDefinition(),
                    new Id019ContainerClosePacketDefinition(),
                    new Id020ContainerSlotStateChangedPacketDefinition(),
                    new Id024EditBookPacketDefinition(),
                    new Id036PickItemFromBlockPacketDefinition(),
                    new Id037PickItemFromEntityPacketDefinition(),
                    new Id039PlaceRecipePacketDefinition(),
                    new Id046RecipeBookChangeSettingsPacketDefinition(),
                    new Id047RecipeBookSeenRecipePacketDefinition(),
                    new Id048RenameItemPacketDefinition(),
                    new Id051SelectTradePacketDefinition(),
                    new Id052SetBeaconPacketDefinition(),
                    new Id053SetCarriedItemPacketDefinition(),
                    new Id056SetCreativeModeSlotPacketDefinition());

    private InventoryServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
