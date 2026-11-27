package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import dev.voldechse.replayframework.adapter.paper.playback.scene.SceneChange;
import dev.voldechse.replayframework.adapter.paper.playback.scene.SceneDecoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.argus.Paper26_2BlockStates;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.papermc.paper.adventure.PaperAdventure;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntPredicate;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.potion.CraftPotionEffectType;
import org.bukkit.potion.PotionEffect;

/**
 * Turns Paper 26.2 packets into the changes of the shared scene model.
 *
 * <p>Relative movement is read from the payload without decoding; the other
 * packets of an entity are decoded only when the scene knows the entity.
 * What changes between versions lives here: the packet classes, the
 * metadata IDs, the wire format of movement and the conversion of items and
 * effects.</p>
 */
final class Paper26_2SceneDecoder implements SceneDecoder {

    private static final String PREFIX = "play.clientbound.minecraft:";
    /** {@code Entity.DATA_SHARED_FLAGS_ID}: a byte of visible states. */
    static final int SHARED_FLAGS = 0;
    /** {@code Entity.DATA_CUSTOM_NAME}: the optional custom name. */
    static final int CUSTOM_NAME = 2;
    private static final int HEALTH = LivingEntity.DATA_HEALTH_ID.id();

    private final Function<RawPacketFrame, Object> decoder;

    /** @param decoder decodes a frame into its native packet */
    Paper26_2SceneDecoder(Function<RawPacketFrame, Object> decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    @Override
    public List<SceneChange> decode(RawPacketFrame frame, String typeName, IntPredicate knownEntity) {
        if (!typeName.startsWith(PREFIX)) {
            return List.of();
        }
        return switch (typeName.substring(PREFIX.length())) {
            case "player_info_update" -> names(decoder.apply(frame));
            case "add_entity" -> spawn(frame, decoder.apply(frame));
            case "move_entity_pos" -> move(frame, knownEntity, true, false);
            case "move_entity_pos_rot" -> move(frame, knownEntity, true, true);
            case "move_entity_rot" -> move(frame, knownEntity, false, true);
            case "entity_position_sync", "teleport_entity", "set_entity_data", "set_equipment",
                    "update_mob_effect", "remove_mob_effect", "update_attributes" ->
                    knownEntity.test(entityId(frame)) ? entityChanges(decoder.apply(frame)) : List.of();
            case "remove_entities" -> decoder.apply(frame) instanceof ClientboundRemoveEntitiesPacket remove
                    ? List.of(new SceneChange.Removed(List.copyOf(remove.getEntityIds())))
                    : List.of();
            case "level_chunk_with_light", "forget_level_chunk", "block_update", "section_blocks_update" ->
                    frame.world().isPresent() ? blocks(frame.world().get(), decoder.apply(frame)) : List.of();
            default -> List.of();
        };
    }

    private static List<SceneChange> names(Object packet) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket update)) {
            return List.of();
        }
        List<SceneChange> changes = new ArrayList<>();
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : update.entries()) {
            GameProfile profile = entry.profile();
            if (profile != null) {
                changes.add(new SceneChange.PlayerName(entry.profileId(), profile.name()));
            }
        }
        return changes;
    }

    private static List<SceneChange> spawn(RawPacketFrame frame, Object packet) {
        if (!(packet instanceof ClientboundAddEntityPacket add) || frame.world().isEmpty()) {
            return List.of();
        }
        return List.of(new SceneChange.Spawn(
                add.getId(), add.getUUID(),
                PaperAdventure.asAdventure(BuiltInRegistries.ENTITY_TYPE.getKey(add.getType())),
                frame.world().get(),
                new SceneChange.Position(add.getX(), add.getY(), add.getZ(), add.getYRot(), add.getXRot())));
    }

    /**
     * Relative movement: entity ID, then three signed shorts in 1/4096
     * blocks when it moves, then yaw and pitch bytes when it turns.
     */
    private static List<SceneChange> move(
            RawPacketFrame frame, IntPredicate knownEntity, boolean position, boolean rotation) {
        byte[] payload = frame.payload();
        int[] cursor = {0};
        int id = readVarInt(payload, cursor);
        int needed = (position ? 6 : 0) + (rotation ? 2 : 0);
        if (!knownEntity.test(id) || payload.length < cursor[0] + needed) {
            return List.of();
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload, cursor[0], needed);
        double dx = 0;
        double dy = 0;
        double dz = 0;
        if (position) {
            dx = buffer.getShort() / 4096.0;
            dy = buffer.getShort() / 4096.0;
            dz = buffer.getShort() / 4096.0;
        }
        Optional<SceneChange.Rotation> turned = rotation
                ? Optional.of(new SceneChange.Rotation(buffer.get() * 360.0F / 256.0F, buffer.get() * 360.0F / 256.0F))
                : Optional.empty();
        return List.of(new SceneChange.Move(id, dx, dy, dz, turned));
    }

    private static List<SceneChange> entityChanges(Object packet) {
        return switch (packet) {
            case ClientboundEntityPositionSyncPacket sync ->
                    List.of(new SceneChange.Teleport(sync.id(), position(sync.values()), Set.of()));
            case ClientboundTeleportEntityPacket teleport -> List.of(new SceneChange.Teleport(
                    teleport.id(), position(teleport.change()), axes(teleport.relatives())));
            case ClientboundSetEntityDataPacket data -> metadata(data);
            case ClientboundSetEquipmentPacket equipped -> {
                Map<org.bukkit.inventory.EquipmentSlot, org.bukkit.inventory.ItemStack> slots =
                        new EnumMap<>(org.bukkit.inventory.EquipmentSlot.class);
                for (Pair<EquipmentSlot, ItemStack> slot : equipped.getSlots()) {
                    slots.put(CraftEquipmentSlot.getSlot(slot.getFirst()), CraftItemStack.asBukkitCopy(slot.getSecond()));
                }
                yield List.of(new SceneChange.Equipment(equipped.getEntity(), slots));
            }
            case ClientboundUpdateMobEffectPacket effect -> List.of(new SceneChange.EffectAdded(
                    effect.getEntityId(),
                    new PotionEffect(
                            CraftPotionEffectType.minecraftHolderToBukkit(effect.getEffect()),
                            effect.getEffectDurationTicks(),
                            effect.getEffectAmplifier(),
                            effect.isEffectAmbient(),
                            effect.isEffectVisible(),
                            effect.effectShowsIcon())));
            case ClientboundRemoveMobEffectPacket removed -> List.of(new SceneChange.EffectRemoved(
                    removed.entityId(), CraftPotionEffectType.minecraftHolderToBukkit(removed.effect())));
            case ClientboundUpdateAttributesPacket attributes -> {
                List<SceneChange> changes = new ArrayList<>();
                for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : attributes.getValues()) {
                    if (snapshot.attribute().equals(Attributes.MAX_HEALTH)) {
                        changes.add(new SceneChange.MaxHealth(
                                attributes.getEntityId(), snapshot.base(), modifiers(snapshot)));
                    }
                }
                yield changes;
            }
            default -> List.of();
        };
    }

    private static List<SceneChange> metadata(ClientboundSetEntityDataPacket data) {
        List<SceneChange> changes = new ArrayList<>();
        for (SynchedEntityData.DataValue<?> value : data.packedItems()) {
            if (value.id() == SHARED_FLAGS && value.value() instanceof Byte bits) {
                changes.add(new SceneChange.Flags(data.id(), bits));
            } else if (value.id() == CUSTOM_NAME && value.value() instanceof Optional<?> custom) {
                changes.add(new SceneChange.CustomName(data.id(), custom
                        .filter(Component.class::isInstance)
                        .map(Component.class::cast)
                        .map(PaperAdventure::asAdventure)));
            } else if (value.id() == HEALTH && value.value() instanceof Float health) {
                changes.add(new SceneChange.Health(data.id(), health));
            }
        }
        return changes;
    }

    private static List<SceneChange> blocks(String world, Object packet) {
        return switch (packet) {
            case ClientboundLevelChunkWithLightPacket chunk -> {
                Map<BlockPosition, Key> containers = new HashMap<>();
                chunk.getChunkData().getBlockEntitiesTagsConsumer(chunk.getX(), chunk.getZ())
                        .accept((pos, type, tag) -> {
                            if (Paper26_2BlockStates.recorded(type)) {
                                containers.put(position(pos), key(type));
                            }
                        });
                yield List.of(new SceneChange.ChunkContainers(world, chunk.getX(), chunk.getZ(), containers));
            }
            case ClientboundForgetLevelChunkPacket forget ->
                    List.of(new SceneChange.ChunkForgotten(world, forget.pos().x(), forget.pos().z()));
            case ClientboundBlockUpdatePacket update -> List.of(block(world, update.getPos(), update.getBlockState()));
            case ClientboundSectionBlocksUpdatePacket section -> {
                List<SceneChange> changes = new ArrayList<>();
                section.runUpdates((pos, state) -> changes.add(block(world, pos, state)));
                yield changes;
            }
            default -> List.of();
        };
    }

    private static SceneChange block(String world, BlockPos pos, BlockState state) {
        return new SceneChange.BlockChanged(
                world, position(pos), Paper26_2BlockStates.recordedType(state).map(Paper26_2SceneDecoder::key));
    }

    private static SceneChange.Position position(PositionMoveRotation values) {
        return new SceneChange.Position(
                values.position().x, values.position().y, values.position().z, values.yRot(), values.xRot());
    }

    private static Set<SceneChange.Axis> axes(Set<Relative> relatives) {
        Set<SceneChange.Axis> axes = EnumSet.noneOf(SceneChange.Axis.class);
        if (relatives.contains(Relative.X)) {
            axes.add(SceneChange.Axis.X);
        }
        if (relatives.contains(Relative.Y)) {
            axes.add(SceneChange.Axis.Y);
        }
        if (relatives.contains(Relative.Z)) {
            axes.add(SceneChange.Axis.Z);
        }
        if (relatives.contains(Relative.Y_ROT)) {
            axes.add(SceneChange.Axis.YAW);
        }
        if (relatives.contains(Relative.X_ROT)) {
            axes.add(SceneChange.Axis.PITCH);
        }
        return axes;
    }

    private static List<SceneChange.Modifier> modifiers(ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot) {
        List<SceneChange.Modifier> modifiers = new ArrayList<>();
        for (AttributeModifier modifier : snapshot.modifiers()) {
            SceneChange.Modifier.Operation operation = switch (modifier.operation()) {
                case ADD_VALUE -> SceneChange.Modifier.Operation.ADD;
                case ADD_MULTIPLIED_BASE -> SceneChange.Modifier.Operation.MULTIPLY_BASE;
                case ADD_MULTIPLIED_TOTAL -> SceneChange.Modifier.Operation.MULTIPLY_TOTAL;
            };
            modifiers.add(new SceneChange.Modifier(operation, modifier.amount()));
        }
        return modifiers;
    }

    private static BlockPosition position(BlockPos pos) {
        return new BlockPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    private static Key key(BlockEntityType<?> type) {
        return Key.key(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
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
}
