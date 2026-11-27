package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/**
 * Builds the state of a subject from the native packets of its state track,
 * applied in order like a client applies them.
 *
 * <p>A player's state is its inventory window (window 0), changed by single
 * slots and by direct inventory slots, its selected slot, health and
 * experience. Its armor and off hand become equipment, the hotbar and the
 * main inventory become sections. An entity's state is the content of its
 * container.</p>
 */
final class Paper26_3SubjectStateDecoder {

    private static final EquipmentSlot[] ARMOR_BY_MENU_SLOT = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private Paper26_3SubjectStateDecoder() {
    }

    /** Applies the packets of a state track in order. */
    static Optional<SubjectState> decode(RecordedSubject subject, List<Object> packets) {
        return subject.kind() == RecordedSubject.Kind.PLAYER ? player(packets) : entity(packets);
    }

    private static Optional<SubjectState> player(List<Object> packets) {
        ItemStack[] hotbar = empty(Inventory.SELECTION_SIZE);
        ItemStack[] main = empty(Inventory.INVENTORY_SIZE - Inventory.SELECTION_SIZE);
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        int selected = 0;
        Optional<SubjectState.Vitals> vitals = Optional.empty();
        Optional<SubjectState.Experience> experience = Optional.empty();
        boolean known = false;
        for (Object packet : packets) {
            switch (packet) {
                case ClientboundContainerSetContentPacket content when content.containerId() == 0 -> {
                    List<ItemStack> items = content.items();
                    for (int slot = 0; slot < items.size(); slot++) {
                        menuSlot(slot, items.get(slot), hotbar, main, equipment);
                    }
                    known = true;
                }
                case ClientboundContainerSetSlotPacket slot when slot.getContainerId() == 0 -> {
                    menuSlot(slot.getSlot(), slot.getItem(), hotbar, main, equipment);
                    known = true;
                }
                case ClientboundSetPlayerInventoryPacket slot -> {
                    inventorySlot(slot.slot(), slot.contents(), hotbar, main, equipment);
                    known = true;
                }
                case ClientboundSetHeldSlotPacket held -> {
                    selected = held.slot();
                    known = true;
                }
                case ClientboundSetHealthPacket health -> {
                    vitals = Optional.of(new SubjectState.Vitals(
                            health.getHealth(), health.getFood(), health.getSaturation()));
                    known = true;
                }
                case ClientboundSetExperiencePacket xp -> {
                    experience = Optional.of(new SubjectState.Experience(
                            xp.getExperienceLevel(), xp.getExperienceProgress(), xp.getTotalExperience()));
                    known = true;
                }
                default -> { }
            }
        }
        if (!known) {
            return Optional.empty();
        }
        if (selected >= 0 && selected < hotbar.length) {
            equipment.put(EquipmentSlot.MAINHAND, hotbar[selected]);
        }
        return Optional.of(new SubjectState(
                bukkit(equipment),
                List.of(
                        new SubjectState.Section(SubjectState.HOTBAR, bukkit(hotbar),
                                selected >= 0 && selected < hotbar.length ? OptionalInt.of(selected) : OptionalInt.empty()),
                        new SubjectState.Section(SubjectState.INVENTORY, bukkit(main), OptionalInt.empty())),
                vitals,
                experience));
    }

    private static Optional<SubjectState> entity(List<Object> packets) {
        List<ItemStack> container = null;
        for (Object packet : packets) {
            if (packet instanceof ClientboundContainerSetContentPacket content) {
                container = content.items();
            }
        }
        if (container == null) {
            return Optional.empty();
        }
        return Optional.of(new SubjectState(
                Map.of(),
                List.of(new SubjectState.Section(
                        SubjectState.CONTAINER, bukkit(container.toArray(ItemStack[]::new)), OptionalInt.empty())),
                Optional.empty(),
                Optional.empty()));
    }

    /** A slot of the inventory window: crafting, armor, main, hotbar, off hand. */
    private static void menuSlot(
            int slot, ItemStack item, ItemStack[] hotbar, ItemStack[] main, Map<EquipmentSlot, ItemStack> equipment) {
        if (slot >= InventoryMenu.ARMOR_SLOT_START && slot < InventoryMenu.ARMOR_SLOT_END) {
            equipment.put(ARMOR_BY_MENU_SLOT[slot - InventoryMenu.ARMOR_SLOT_START], item.copy());
        } else if (slot >= InventoryMenu.INV_SLOT_START && slot < InventoryMenu.INV_SLOT_END) {
            main[slot - InventoryMenu.INV_SLOT_START] = item.copy();
        } else if (slot >= InventoryMenu.USE_ROW_SLOT_START && slot < InventoryMenu.USE_ROW_SLOT_END) {
            hotbar[slot - InventoryMenu.USE_ROW_SLOT_START] = item.copy();
        } else if (slot == InventoryMenu.SHIELD_SLOT) {
            equipment.put(EquipmentSlot.OFFHAND, item.copy());
        }
    }

    /** A slot in the numbering of the player's inventory: hotbar, main, then equipment. */
    private static void inventorySlot(
            int slot, ItemStack item, ItemStack[] hotbar, ItemStack[] main, Map<EquipmentSlot, ItemStack> equipment) {
        if (slot >= 0 && slot < Inventory.SELECTION_SIZE) {
            hotbar[slot] = item.copy();
        } else if (slot >= Inventory.SELECTION_SIZE && slot < Inventory.INVENTORY_SIZE) {
            main[slot - Inventory.SELECTION_SIZE] = item.copy();
        } else {
            EquipmentSlot equipped = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);
            if (equipped != null) {
                equipment.put(equipped, item.copy());
            }
        }
    }

    private static ItemStack[] empty(int size) {
        ItemStack[] items = new ItemStack[size];
        java.util.Arrays.fill(items, ItemStack.EMPTY);
        return items;
    }

    private static List<org.bukkit.inventory.ItemStack> bukkit(ItemStack[] items) {
        List<org.bukkit.inventory.ItemStack> converted = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            converted.add(CraftItemStack.asBukkitCopy(item));
        }
        return Collections.unmodifiableList(converted);
    }

    private static Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> bukkit(
            Map<EquipmentSlot, ItemStack> equipment) {
        Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> converted =
                new EnumMap<>(org.bukkit.inventory.EquipmentSlot.class);
        equipment.forEach((slot, item) -> {
            if (!item.isEmpty()) {
                converted.put(CraftEquipmentSlot.getSlot(slot), CraftItemStack.asBukkitCopy(item));
            }
        });
        return converted;
    }
}
