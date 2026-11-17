package dev.voldechse.replayframework.api.playback;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * The state of a subject at the playback position: what a client knows
 * about itself, or what it would see when opening the subject.
 *
 * <p>Equipment is described like the equipment of every entity in the
 * scene. Everything else a subject holds is a list of sections, each named by
 * a key. Which sections exist depends on the subject: a player has its hotbar
 * and its inventory, a chest minecart or a llama its container. Use
 * {@link #section(Key)} for a known one, or show all of them in order.</p>
 *
 * @param equipment worn and held items by slot; empty slots are left out
 * @param sections the subject's other item sections, in display order
 * @param vitals health and food, for subjects that have them
 * @param experience experience, for subjects that have it
 */
public record SubjectState(
        Map<EquipmentSlot, ItemStack> equipment,
        List<Section> sections,
        Optional<Vitals> vitals,
        Optional<Experience> experience) {

    /** A player's hotbar; its selected slot is the held one. */
    public static final Key HOTBAR = Key.key("minecraft:hotbar");
    /** A player's main inventory, or the inventory a mob carries. */
    public static final Key INVENTORY = Key.key("minecraft:inventory");
    /** The content of a container entity, such as a chest minecart or a chested llama. */
    public static final Key CONTAINER = Key.key("minecraft:container");

    public SubjectState {
        Objects.requireNonNull(equipment, "equipment");
        Map<EquipmentSlot, ItemStack> copy = new EnumMap<>(EquipmentSlot.class);
        equipment.forEach((slot, item) -> copy.put(
                Objects.requireNonNull(slot, "slot"), Objects.requireNonNull(item, "item").clone()));
        equipment = java.util.Collections.unmodifiableMap(copy);
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        Objects.requireNonNull(vitals, "vitals");
        Objects.requireNonNull(experience, "experience");
    }

    /** The section with the given key. */
    public Optional<Section> section(Key id) {
        Objects.requireNonNull(id, "id");
        return sections.stream().filter(section -> section.id().equals(id)).findFirst();
    }

    /**
     * One named group of item slots.
     *
     * @param id what the section is, such as {@link #HOTBAR}
     * @param items one entry per slot; an empty slot holds an empty item
     * @param selected the selected slot, for a section that has one
     */
    public record Section(Key id, List<ItemStack> items, OptionalInt selected) {
        public Section {
            Objects.requireNonNull(id, "id");
            items = Objects.requireNonNull(items, "items").stream()
                    .map(item -> Objects.requireNonNull(item, "item").clone())
                    .toList();
            Objects.requireNonNull(selected, "selected");
            if (selected.isPresent() && (selected.getAsInt() < 0 || selected.getAsInt() >= items.size())) {
                throw new IllegalArgumentException("selected slot is outside the section");
            }
        }
    }

    /**
     * Health and food.
     *
     * @param health current health points
     * @param food food level, 0 to 20
     * @param saturation saturation level
     */
    public record Vitals(double health, int food, float saturation) {
    }

    /**
     * Experience.
     *
     * @param level experience level
     * @param progress progress to the next level, 0 to 1
     * @param total total experience points
     */
    public record Experience(int level, float progress, int total) {
    }
}
