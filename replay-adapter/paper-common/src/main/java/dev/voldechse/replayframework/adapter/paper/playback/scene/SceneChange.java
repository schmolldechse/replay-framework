package dev.voldechse.replayframework.adapter.paper.playback.scene;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * One change of the scene a replayed packet describes, free of Minecraft
 * classes. A version's {@link SceneDecoder} turns packets into changes; the
 * shared {@link SceneModel} applies them. Entities are named by their
 * recorded entity ID.
 */
public sealed interface SceneChange {

    /** A player profile the tab list names. */
    record PlayerName(UUID id, String name) implements SceneChange {
        public PlayerName {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /** An entity appears in a world. */
    record Spawn(int entityId, UUID id, Key type, String world, Position position) implements SceneChange {
        public Spawn {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
        }
    }

    /**
     * An entity moves by a delta and may turn.
     *
     * @param rotation the new absolute rotation, if the entity turned
     */
    record Move(int entityId, double dx, double dy, double dz, Optional<Rotation> rotation) implements SceneChange {
        public Move {
            Objects.requireNonNull(rotation, "rotation");
        }
    }

    /**
     * An entity is placed. Each axis in {@code relative} is added to the
     * current value instead of replacing it.
     */
    record Teleport(int entityId, Position position, Set<Axis> relative) implements SceneChange {
        public Teleport {
            Objects.requireNonNull(position, "position");
            relative = Set.copyOf(relative);
        }
    }

    /** The visible state flags of an entity, as the shared flags byte. */
    record Flags(int entityId, byte bits) implements SceneChange {
    }

    /** The custom name of an entity; empty removes it. */
    record CustomName(int entityId, Optional<Component> name) implements SceneChange {
        public CustomName {
            Objects.requireNonNull(name, "name");
        }
    }

    /** The health of a living entity. */
    record Health(int entityId, double health) implements SceneChange {
    }

    /** The maximum health of a living entity: a base value and its modifiers. */
    record MaxHealth(int entityId, double base, List<Modifier> modifiers) implements SceneChange {
        public MaxHealth {
            modifiers = List.copyOf(modifiers);
        }
    }

    /** Items an entity wears or holds; an empty item clears its slot. */
    record Equipment(int entityId, Map<EquipmentSlot, ItemStack> slots) implements SceneChange {
        public Equipment {
            slots = Map.copyOf(slots);
        }
    }

    /** An effect is added to an entity or replaces one of the same type. */
    record EffectAdded(int entityId, PotionEffect effect) implements SceneChange {
        public EffectAdded {
            Objects.requireNonNull(effect, "effect");
        }
    }

    /** An effect ends. */
    record EffectRemoved(int entityId, PotionEffectType type) implements SceneChange {
        public EffectRemoved {
            Objects.requireNonNull(type, "type");
        }
    }

    /** Entities leave the scene. */
    record Removed(List<Integer> entityIds) implements SceneChange {
        public Removed {
            entityIds = List.copyOf(entityIds);
        }
    }

    /** A chunk is sent with the blocks of it whose contents the recording keeps. */
    record ChunkContainers(String world, int chunkX, int chunkZ, Map<BlockPosition, Key> containers)
            implements SceneChange {
        public ChunkContainers {
            Objects.requireNonNull(world, "world");
            containers = Map.copyOf(containers);
        }
    }

    /** A chunk leaves the scene with its blocks. */
    record ChunkForgotten(String world, int chunkX, int chunkZ) implements SceneChange {
        public ChunkForgotten {
            Objects.requireNonNull(world, "world");
        }
    }

    /** A block changes; {@code container} is its type when the recording keeps its contents. */
    record BlockChanged(String world, BlockPosition position, Optional<Key> container) implements SceneChange {
        public BlockChanged {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(container, "container");
        }
    }

    /** An absolute position and rotation. */
    record Position(double x, double y, double z, float yaw, float pitch) {
    }

    /** An absolute rotation. */
    record Rotation(float yaw, float pitch) {
    }

    /** The axes of a teleport. */
    enum Axis { X, Y, Z, YAW, PITCH }

    /** An attribute modifier, in the order the game applies them. */
    record Modifier(Operation operation, double amount) {
        public Modifier {
            Objects.requireNonNull(operation, "operation");
        }

        /** How a modifier changes an attribute value. */
        public enum Operation {
            /** Added to the base value. */
            ADD,
            /** Multiplied with the base value after additions, then added. */
            MULTIPLY_BASE,
            /** Multiplies the total with one plus its amount. */
            MULTIPLY_TOTAL
        }
    }
}
