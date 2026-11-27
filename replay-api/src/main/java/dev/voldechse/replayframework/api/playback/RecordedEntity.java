package dev.voldechse.replayframework.api.playback;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * What the scene shows of an entity at the playback position: what every
 * client in range receives about it.
 *
 * @param id the entity's UUID; a player's profile UUID
 * @param type the entity type
 * @param location where it is in the replay
 * @param customName its name, when it has one
 * @param health its health, when the scene shows it
 * @param maxHealth its maximum health, when the scene shows it
 * @param equipment worn and held items by slot
 * @param effects its active effects
 * @param flags visible states
 * @param subject its state track, when the recording has one; query it with
 *                {@link PlaybackSession#state(RecordedSubject)}
 */
public record RecordedEntity(
        UUID id,
        Key type,
        RecordedPlayer.Location location,
        Optional<Component> customName,
        OptionalDouble health,
        OptionalDouble maxHealth,
        Map<EquipmentSlot, ItemStack> equipment,
        List<PotionEffect> effects,
        Set<Flag> flags,
        Optional<RecordedSubject> subject) {

    public RecordedEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(customName, "customName");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(maxHealth, "maxHealth");
        Map<EquipmentSlot, ItemStack> copy = new EnumMap<>(EquipmentSlot.class);
        Objects.requireNonNull(equipment, "equipment").forEach((slot, item) -> copy.put(slot, item.clone()));
        equipment = java.util.Collections.unmodifiableMap(copy);
        effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
        flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
        Objects.requireNonNull(subject, "subject");
    }

    /** Returns this entity with the given state track subject. */
    public RecordedEntity withSubject(Optional<RecordedSubject> stateSubject) {
        return new RecordedEntity(id, type, location, customName, health, maxHealth,
                equipment, effects, flags, stateSubject);
    }

    /** States every client sees of an entity. */
    public enum Flag {
        ON_FIRE,
        SNEAKING,
        SPRINTING,
        SWIMMING,
        INVISIBLE,
        GLOWING,
        GLIDING
    }
}
