package dev.voldechse.replayframework.adapter.paper.v26_3.packet.serverbound.definition.inventory;

import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import java.util.List;

/** Immutable Paper 26.3 serverbound definitions owned by the inventory protocol domain. */
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
                    new Id047RecipeBookChangeSettingsPacketDefinition(),
                    new Id048RecipeBookSeenRecipePacketDefinition(),
                    new Id049RenameItemPacketDefinition(),
                    new Id052SelectTradePacketDefinition(),
                    new Id053SetBeaconPacketDefinition(),
                    new Id054SetCarriedItemPacketDefinition(),
                    new Id057SetCreativeModeSlotPacketDefinition());

    private InventoryServerboundPacketDefinitions() {}

    public static List<ServerboundPacketDefinition> definitions() {
        return DEFINITIONS;
    }
}
