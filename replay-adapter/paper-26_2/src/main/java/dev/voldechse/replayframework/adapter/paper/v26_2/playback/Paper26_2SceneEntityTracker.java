package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.papermc.paper.adventure.PaperAdventure;
import java.nio.ByteBuffer;
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
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.potion.CraftPotionEffectType;
import org.bukkit.potion.PotionEffect;

/**
 * Knows what the scene shows of every entity of a replay at the current
 * playback position: where it is and what every client in range receives
 * about it.
 *
 * <p>It reads the frames before playback filters them by world, so it also
 * follows entities in worlds the viewer does not watch. Entities are kept by
 * their recorded entity ID. Relative movement is read from the payload
 * without decoding; the other packets of an entity are decoded only when the
 * entity is known.</p>
 *
 * <p>A seek resets the view and replays checkpoint and frames, so the tracker
 * is cleared together with the view and rebuilds itself.</p>
 */
final class Paper26_2SceneEntityTracker {

    private static final String PREFIX = "play.clientbound.minecraft:";
    /** {@code Entity.DATA_SHARED_FLAGS_ID}: a byte of visible states. */
    static final int SHARED_FLAGS = 0;
    /** {@code Entity.DATA_CUSTOM_NAME}: the optional custom name. */
    static final int CUSTOM_NAME = 2;

    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<Integer, TrackedEntity> entities = new HashMap<>();

    /**
     * Observes one frame.
     *
     * @param typeName the frame's descriptor type name
     * @param decoder decodes the frame into its native packet
     */
    synchronized void observe(RawPacketFrame frame, String typeName, Function<RawPacketFrame, Object> decoder) {
        if (!typeName.startsWith(PREFIX)) {
            return;
        }
        switch (typeName.substring(PREFIX.length())) {
            case "player_info_update" -> rememberNames(decoder.apply(frame));
            case "add_entity" -> spawn(frame, decoder.apply(frame));
            case "move_entity_pos" -> move(frame, true, false);
            case "move_entity_pos_rot" -> move(frame, true, true);
            case "move_entity_rot" -> move(frame, false, true);
            case "entity_position_sync", "teleport_entity", "set_entity_data", "set_equipment",
                    "update_mob_effect", "remove_mob_effect", "update_attributes" -> {
                TrackedEntity entity = entities.get(entityId(frame));
                if (entity != null) {
                    entity.apply(decoder.apply(frame));
                }
            }
            case "remove_entities" -> {
                if (decoder.apply(frame) instanceof ClientboundRemoveEntitiesPacket remove) {
                    remove.getEntityIds().forEach((int id) -> entities.remove(id));
                }
            }
            default -> { }
        }
    }

    /** Forgets everything; the frames after a view reset rebuild it. */
    synchronized void clear() {
        names.clear();
        entities.clear();
    }

    /** Every known player, sorted by name, with its location when it is in the scene. */
    synchronized List<RecordedPlayer> players() {
        Map<UUID, RecordedPlayer.Location> locations = new HashMap<>();
        for (TrackedEntity entity : entities.values()) {
            if (entity.type == EntityTypes.PLAYER) {
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
    synchronized Optional<RecordedEntity> entity(int recordedId) {
        return Optional.ofNullable(entities.get(recordedId)).map(TrackedEntity::view);
    }

    /** Every entity of one recorded world. */
    synchronized List<RecordedEntity> entities(String world) {
        return entities.values().stream()
                .filter(entity -> entity.world.equals(world))
                .map(TrackedEntity::view)
                .toList();
    }

    private void rememberNames(Object packet) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket update)) {
            return;
        }
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : update.entries()) {
            GameProfile profile = entry.profile();
            if (profile != null) {
                names.put(entry.profileId(), profile.name());
            }
        }
    }

    private void spawn(RawPacketFrame frame, Object packet) {
        if (!(packet instanceof ClientboundAddEntityPacket add) || frame.world().isEmpty()) {
            return;
        }
        entities.put(add.getId(), new TrackedEntity(
                add.getUUID(), add.getType(), frame.world().get(),
                add.getX(), add.getY(), add.getZ(), add.getYRot(), add.getXRot()));
    }

    /**
     * Relative movement: entity ID, then three signed shorts in 1/4096
     * blocks when it moves, then yaw and pitch bytes when it turns.
     */
    private void move(RawPacketFrame frame, boolean position, boolean rotation) {
        byte[] payload = frame.payload();
        int[] cursor = {0};
        int id = readVarInt(payload, cursor);
        TrackedEntity entity = entities.get(id);
        int needed = (position ? 6 : 0) + (rotation ? 2 : 0);
        if (entity == null || payload.length < cursor[0] + needed) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload, cursor[0], needed);
        if (position) {
            entity.x += buffer.getShort() / 4096.0;
            entity.y += buffer.getShort() / 4096.0;
            entity.z += buffer.getShort() / 4096.0;
        }
        if (rotation) {
            entity.yaw = buffer.get() * 360.0F / 256.0F;
            entity.pitch = buffer.get() * 360.0F / 256.0F;
        }
    }

    private static int entityId(RawPacketFrame frame) {
        return readVarInt(frame.payload(), new int[] {0});
    }

    private static int readVarInt(byte[] bytes, int[] cursor) {
        int value = 0;
        int shift = 0;
        while (cursor[0] < bytes.length && shift < 35) {
            int next = bytes[cursor[0]++] & 0xFF;
            value |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    /** One entity of the replay and the last scene state of it. */
    private static final class TrackedEntity {
        private final UUID id;
        private final EntityType<?> type;
        private final String world;
        private final Map<Integer, Object> data = new HashMap<>();
        private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        private final Map<Holder<MobEffect>, ClientboundUpdateMobEffectPacket> effects = new LinkedHashMap<>();
        private Double maxHealth;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private TrackedEntity(
                UUID id, EntityType<?> type, String world,
                double x, double y, double z, float yaw, float pitch) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = Objects.requireNonNull(type, "type");
            this.world = Objects.requireNonNull(world, "world");
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private void apply(Object packet) {
            switch (packet) {
                case ClientboundEntityPositionSyncPacket sync -> place(sync.values());
                case ClientboundTeleportEntityPacket teleport -> place(PositionMoveRotation.calculateAbsolute(
                        new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch),
                        teleport.change(), teleport.relatives()));
                case ClientboundSetEntityDataPacket entityData -> {
                    for (SynchedEntityData.DataValue<?> value : entityData.packedItems()) {
                        data.put(value.id(), value.value());
                    }
                }
                case ClientboundSetEquipmentPacket equipped -> {
                    for (Pair<EquipmentSlot, ItemStack> slot : equipped.getSlots()) {
                        equipment.put(slot.getFirst(), slot.getSecond().copy());
                    }
                }
                case ClientboundUpdateMobEffectPacket effect -> effects.put(effect.getEffect(), effect);
                case ClientboundRemoveMobEffectPacket removed -> effects.remove(removed.effect());
                case ClientboundUpdateAttributesPacket attributes -> {
                    for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : attributes.getValues()) {
                        if (snapshot.attribute().equals(Attributes.MAX_HEALTH)) {
                            maxHealth = value(snapshot);
                        }
                    }
                }
                default -> { }
            }
        }

        private void place(PositionMoveRotation values) {
            x = values.position().x;
            y = values.position().y;
            z = values.position().z;
            yaw = values.yRot();
            pitch = values.xRot();
        }

        private RecordedPlayer.Location location() {
            return new RecordedPlayer.Location(Key.key(world), x, y, z, yaw, pitch);
        }

        private RecordedEntity view() {
            Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> worn =
                    new EnumMap<>(org.bukkit.inventory.EquipmentSlot.class);
            equipment.forEach((slot, item) -> {
                if (!item.isEmpty()) {
                    worn.put(CraftEquipmentSlot.getSlot(slot), CraftItemStack.asBukkitCopy(item));
                }
            });
            List<PotionEffect> active = new ArrayList<>();
            for (ClientboundUpdateMobEffectPacket effect : effects.values()) {
                active.add(new PotionEffect(
                        CraftPotionEffectType.minecraftHolderToBukkit(effect.getEffect()),
                        effect.getEffectDurationTicks(),
                        effect.getEffectAmplifier(),
                        effect.isEffectAmbient(),
                        effect.isEffectVisible(),
                        effect.effectShowsIcon()));
            }
            Optional<net.kyori.adventure.text.Component> name = data.get(CUSTOM_NAME) instanceof Optional<?> custom
                    ? custom.filter(Component.class::isInstance).map(Component.class::cast).map(PaperAdventure::asAdventure)
                    : Optional.empty();
            OptionalDouble health = data.get(LivingEntity.DATA_HEALTH_ID.id()) instanceof Float value
                    ? OptionalDouble.of(value)
                    : OptionalDouble.empty();
            return new RecordedEntity(
                    id,
                    PaperAdventure.asAdventure(BuiltInRegistries.ENTITY_TYPE.getKey(type)),
                    location(),
                    name,
                    health,
                    maxHealth == null ? OptionalDouble.empty() : OptionalDouble.of(maxHealth),
                    worn,
                    active,
                    flags(),
                    Optional.empty());
        }

        private Set<RecordedEntity.Flag> flags() {
            Set<RecordedEntity.Flag> flags = EnumSet.noneOf(RecordedEntity.Flag.class);
            if (!(data.get(SHARED_FLAGS) instanceof Byte bits)) {
                return flags;
            }
            flag(flags, bits, 0, RecordedEntity.Flag.ON_FIRE);
            flag(flags, bits, 1, RecordedEntity.Flag.SNEAKING);
            flag(flags, bits, 3, RecordedEntity.Flag.SPRINTING);
            flag(flags, bits, 4, RecordedEntity.Flag.SWIMMING);
            flag(flags, bits, 5, RecordedEntity.Flag.INVISIBLE);
            flag(flags, bits, 6, RecordedEntity.Flag.GLOWING);
            flag(flags, bits, 7, RecordedEntity.Flag.GLIDING);
            return flags;
        }

        private static void flag(Set<RecordedEntity.Flag> flags, byte bits, int bit, RecordedEntity.Flag flag) {
            if ((bits & (1 << bit)) != 0) {
                flags.add(flag);
            }
        }

        /** The value of an attribute: base, plus additions, times multipliers. */
        private static double value(ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot) {
            double value = snapshot.base();
            for (AttributeModifier modifier : snapshot.modifiers()) {
                if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                    value += modifier.amount();
                }
            }
            double base = value;
            for (AttributeModifier modifier : snapshot.modifiers()) {
                if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
                    value += base * modifier.amount();
                }
            }
            for (AttributeModifier modifier : snapshot.modifiers()) {
                if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
                    value *= 1.0 + modifier.amount();
                }
            }
            return value;
        }
    }
}
