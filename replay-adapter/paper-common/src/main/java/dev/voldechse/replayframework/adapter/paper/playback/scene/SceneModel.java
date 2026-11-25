package dev.voldechse.replayframework.adapter.paper.playback.scene;

import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import org.bukkit.potion.PotionEffectType;

/**
 * Knows what the scene of a replay shows at the current playback position:
 * every entity with where it is and what every client in range receives
 * about it, the names of the players, and the blocks whose contents the
 * recording keeps.
 *
 * <p>It reads the frames before playback filters them by world, so it also
 * follows worlds the viewer does not watch. Entities are kept by their
 * recorded entity ID; a version's {@link SceneDecoder} turns packets into
 * {@link SceneChange changes} and decodes packets of an entity only when the
 * model knows it.</p>
 *
 * <p>A seek resets the view and replays checkpoint and frames, so the model
 * is cleared together with the view and rebuilds itself.</p>
 */
public final class SceneModel {

    private static final Key PLAYER = Key.key("minecraft:player");

    private final SceneDecoder decoder;
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<Integer, Entity> entities = new HashMap<>();
    private final Map<String, Map<BlockPosition, Key>> containers = new HashMap<>();

    /** @param decoder turns the version's packets into scene changes */
    public SceneModel(SceneDecoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Observes one frame of any world.
     *
     * @param typeName the frame's descriptor type name
     */
    public synchronized void observe(RawPacketFrame frame, String typeName) {
        for (SceneChange change : decoder.decode(frame, typeName, entities::containsKey)) {
            apply(change);
        }
    }

    /** Applies one change. */
    public synchronized void apply(SceneChange change) {
        switch (change) {
            case SceneChange.PlayerName name -> names.put(name.id(), name.name());
            case SceneChange.Spawn spawn -> entities.put(spawn.entityId(), new Entity(spawn));
            case SceneChange.Removed removed -> removed.entityIds().forEach(entities::remove);
            case SceneChange.ChunkContainers chunk -> forget(chunk.world(), chunk.chunkX(), chunk.chunkZ())
                    .putAll(chunk.containers());
            case SceneChange.ChunkForgotten chunk -> forget(chunk.world(), chunk.chunkX(), chunk.chunkZ());
            case SceneChange.BlockChanged block -> {
                Map<BlockPosition, Key> known = containers.computeIfAbsent(block.world(), ignored -> new HashMap<>());
                block.container().ifPresentOrElse(
                        type -> known.put(block.position(), type), () -> known.remove(block.position()));
            }
            default -> {
                Entity entity = entities.get(entityId(change));
                if (entity != null) {
                    entity.apply(change);
                }
            }
        }
    }

    /** Forgets everything; the frames after a view reset rebuild it. */
    public synchronized void clear() {
        names.clear();
        entities.clear();
        containers.clear();
    }

    /** Every known player, sorted by name, with its location when it is in the scene. */
    public synchronized List<RecordedPlayer> players() {
        Map<UUID, RecordedPlayer.Location> locations = new HashMap<>();
        for (Entity entity : entities.values()) {
            if (entity.type.equals(PLAYER)) {
                locations.put(entity.id, entity.location());
            }
        }
        Map<UUID, String> known = new LinkedHashMap<>(names);
        locations.keySet().forEach(id -> known.putIfAbsent(id, id.toString()));
        List<RecordedPlayer> players = new ArrayList<>();
        known.forEach((id, name) -> players.add(
                new RecordedPlayer(id, name, Optional.ofNullable(locations.get(id)))));
        players.sort(Comparator.comparing(RecordedPlayer::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(players);
    }

    /** What the scene shows of the entity with this recorded ID. */
    public synchronized Optional<RecordedEntity> entity(int entityId) {
        return Optional.ofNullable(entities.get(entityId)).map(Entity::view);
    }

    /** Every entity of one recorded world. */
    public synchronized List<RecordedEntity> entities(String world) {
        return entities.values().stream()
                .filter(entity -> entity.world.equals(world))
                .map(Entity::view)
                .toList();
    }

    /** The type of the block whose contents the recording keeps, at a position of a world. */
    public synchronized Optional<Key> container(String world, BlockPosition position) {
        return Optional.ofNullable(containers.getOrDefault(world, Map.of()).get(position));
    }

    /** Forgets the containers of a chunk and returns the world's containers. */
    private Map<BlockPosition, Key> forget(String world, int chunkX, int chunkZ) {
        Map<BlockPosition, Key> known = containers.computeIfAbsent(world, ignored -> new HashMap<>());
        known.keySet().removeIf(position -> (position.x() >> 4) == chunkX && (position.z() >> 4) == chunkZ);
        return known;
    }

    private static int entityId(SceneChange change) {
        return switch (change) {
            case SceneChange.Move move -> move.entityId();
            case SceneChange.Teleport teleport -> teleport.entityId();
            case SceneChange.Flags flags -> flags.entityId();
            case SceneChange.CustomName name -> name.entityId();
            case SceneChange.Health health -> health.entityId();
            case SceneChange.MaxHealth maxHealth -> maxHealth.entityId();
            case SceneChange.Equipment equipment -> equipment.entityId();
            case SceneChange.EffectAdded effect -> effect.entityId();
            case SceneChange.EffectRemoved effect -> effect.entityId();
            default -> throw new IllegalArgumentException("not an entity change: " + change);
        };
    }

    /** The value of an attribute: base, plus additions, times multipliers. */
    static double attributeValue(double base, List<SceneChange.Modifier> modifiers) {
        double value = base;
        for (SceneChange.Modifier modifier : modifiers) {
            if (modifier.operation() == SceneChange.Modifier.Operation.ADD) {
                value += modifier.amount();
            }
        }
        double added = value;
        for (SceneChange.Modifier modifier : modifiers) {
            if (modifier.operation() == SceneChange.Modifier.Operation.MULTIPLY_BASE) {
                value += added * modifier.amount();
            }
        }
        for (SceneChange.Modifier modifier : modifiers) {
            if (modifier.operation() == SceneChange.Modifier.Operation.MULTIPLY_TOTAL) {
                value *= 1.0 + modifier.amount();
            }
        }
        return value;
    }

    /** One entity of the replay and the last scene state of it. */
    private static final class Entity {
        private final UUID id;
        private final Key type;
        private final String world;
        private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        private final Map<PotionEffectType, PotionEffect> effects = new LinkedHashMap<>();
        private Optional<Component> customName = Optional.empty();
        private OptionalDouble health = OptionalDouble.empty();
        private OptionalDouble maxHealth = OptionalDouble.empty();
        private byte flags;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private Entity(SceneChange.Spawn spawn) {
            this.id = spawn.id();
            this.type = spawn.type();
            this.world = spawn.world();
            place(spawn.position());
        }

        private void apply(SceneChange change) {
            switch (change) {
                case SceneChange.Move move -> {
                    x += move.dx();
                    y += move.dy();
                    z += move.dz();
                    move.rotation().ifPresent(rotation -> {
                        yaw = rotation.yaw();
                        pitch = rotation.pitch();
                    });
                }
                case SceneChange.Teleport teleport -> {
                    SceneChange.Position to = teleport.position();
                    Set<SceneChange.Axis> relative = teleport.relative();
                    place(new SceneChange.Position(
                            relative.contains(SceneChange.Axis.X) ? x + to.x() : to.x(),
                            relative.contains(SceneChange.Axis.Y) ? y + to.y() : to.y(),
                            relative.contains(SceneChange.Axis.Z) ? z + to.z() : to.z(),
                            relative.contains(SceneChange.Axis.YAW) ? yaw + to.yaw() : to.yaw(),
                            relative.contains(SceneChange.Axis.PITCH) ? pitch + to.pitch() : to.pitch()));
                }
                case SceneChange.Flags changed -> flags = changed.bits();
                case SceneChange.CustomName name -> customName = name.name();
                case SceneChange.Health changed -> health = OptionalDouble.of(changed.health());
                case SceneChange.MaxHealth changed ->
                        maxHealth = OptionalDouble.of(attributeValue(changed.base(), changed.modifiers()));
                case SceneChange.Equipment changed -> equipment.putAll(changed.slots());
                case SceneChange.EffectAdded effect -> effects.put(effect.effect().getType(), effect.effect());
                case SceneChange.EffectRemoved effect -> effects.remove(effect.type());
                default -> { }
            }
        }

        private void place(SceneChange.Position position) {
            x = position.x();
            y = position.y();
            z = position.z();
            yaw = position.yaw();
            pitch = position.pitch();
        }

        private RecordedPlayer.Location location() {
            return new RecordedPlayer.Location(Key.key(world), x, y, z, yaw, pitch);
        }

        private RecordedEntity view() {
            Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
            equipment.forEach((slot, item) -> {
                if (!item.isEmpty()) {
                    worn.put(slot, item.clone());
                }
            });
            return new RecordedEntity(
                    id, type, location(), customName, health, maxHealth, worn,
                    List.copyOf(effects.values()), flags(), Optional.empty());
        }

        private Set<RecordedEntity.Flag> flags() {
            Set<RecordedEntity.Flag> set = EnumSet.noneOf(RecordedEntity.Flag.class);
            flag(set, 0, RecordedEntity.Flag.ON_FIRE);
            flag(set, 1, RecordedEntity.Flag.SNEAKING);
            flag(set, 3, RecordedEntity.Flag.SPRINTING);
            flag(set, 4, RecordedEntity.Flag.SWIMMING);
            flag(set, 5, RecordedEntity.Flag.INVISIBLE);
            flag(set, 6, RecordedEntity.Flag.GLOWING);
            flag(set, 7, RecordedEntity.Flag.GLIDING);
            return set;
        }

        private void flag(Set<RecordedEntity.Flag> set, int bit, RecordedEntity.Flag flag) {
            if ((flags & (1 << bit)) != 0) {
                set.add(flag);
            }
        }
    }
}
