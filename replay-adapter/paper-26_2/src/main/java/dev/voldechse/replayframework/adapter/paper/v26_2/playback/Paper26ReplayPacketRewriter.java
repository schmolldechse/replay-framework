package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

/**
 * Adapter-local isolation boundary between decoded replay packets and the
 * viewer outbound gate. Viewer-local state is dropped; identity-bearing
 * packets are handed to the version-bound identity transformer.
 */
public final class Paper26ReplayPacketRewriter {
    private static final Set<String> VIEWER_LOCAL = Set.of(
            "minecraft:player_position",
            "minecraft:player_rotation",
            "minecraft:player_abilities",
            "minecraft:set_health",
            "minecraft:set_experience",
            "minecraft:respawn",
            "minecraft:container_close",
            "minecraft:container_set_content",
            "minecraft:container_set_data",
            "minecraft:container_set_slot",
            "minecraft:set_cursor_item",
            "minecraft:set_held_slot",
            "minecraft:set_player_inventory",
            "minecraft:move_vehicle",
            "minecraft:set_camera",
            "minecraft:player_look_at",
            "minecraft:set_action_bar_text",
            "minecraft:open_screen",
            "minecraft:mount_screen_open",
            "minecraft:player_combat_end",
            "minecraft:player_combat_enter",
            "minecraft:player_combat_kill",
            "minecraft:tab_list");

    private static final Set<String> IDENTITY_BEARING = Set.of(
            "minecraft:add_entity",
            "minecraft:animate",
            "minecraft:block_destruction",
            "minecraft:boss_event",
            "minecraft:damage_event",
            "minecraft:entity_event",
            "minecraft:entity_position_sync",
            "minecraft:hurt_animation",
            "minecraft:move_entity_pos",
            "minecraft:move_entity_pos_rot",
            "minecraft:move_entity_rot",
            "minecraft:move_minecart_along_track",
            "minecraft:remove_entities",
            "minecraft:rotate_head",
            "minecraft:set_entity_data",
            "minecraft:set_entity_link",
            "minecraft:set_entity_motion",
            "minecraft:set_equipment",
            "minecraft:set_passengers",
            "minecraft:teleport_entity",
            "minecraft:update_attributes",
            "minecraft:update_mob_effect",
            "minecraft:remove_mob_effect",
            "minecraft:player_info_remove",
            "minecraft:player_info_update",
            "minecraft:player_chat",
            "minecraft:projectile_power",
            "minecraft:take_item_entity",
            "minecraft:sound_entity",
            "minecraft:set_objective",
            "minecraft:set_display_objective",
            "minecraft:set_player_team",
            "minecraft:set_score",
            "minecraft:reset_score",
            "minecraft:waypoint");

    private final PacketRegistry registry;
    private final PlaybackIdentityContext context;
    private final ReplayIdentityMap identities;
    private final Set<UUID> activeBossBars = new java.util.HashSet<>();

    Paper26ReplayPacketRewriter(
            PacketRegistry registry,
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds) {
        this(registry, context, reservedUuids, reservedEntityIds, Set.of());
    }

    Paper26ReplayPacketRewriter(
            PacketRegistry registry,
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds,
            Set<String> reservedNames) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.context = Objects.requireNonNull(context, "context");
        this.identities = new ReplayIdentityMap(context, reservedUuids, reservedEntityIds, reservedNames);
    }

    /** Returns an empty result for packets that must not reach the observer. */
    RewriteResult rewrite(Object decodedPacket, PacketDescriptor descriptor) {
        Objects.requireNonNull(decodedPacket, "decodedPacket");
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || !descriptor.replayable()
                || !registry.replayAllowed(
                        descriptor.phase(), descriptor.direction(), descriptor.packetId())) {
            throw incompatible("replay packet is not permitted at the rewrite boundary");
        }
        String packetName = descriptor.typeName();
        if (packetName.startsWith("play.clientbound.")) {
            packetName = packetName.substring("play.clientbound.".length());
        }
        if (isViewerLocal(packetName)
                && descriptor.playbackScope() != PacketDescriptor.PlaybackScope.VIEWER_LOCAL) {
            throw incompatible("viewer-local Paper packet has an invalid playback scope");
        }
        if (isIdentityBearing(packetName)
                && descriptor.playbackScope() != PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES) {
            throw incompatible("identity-bearing Paper packet has an invalid playback scope");
        }
        RewriteResult payloadResult = rewritePayloadSensitive(decodedPacket, packetName);
        if (payloadResult != null) {
            return payloadResult;
        }
        if ("minecraft:boss_event".equals(packetName)) {
            return rewriteBossEvent(decodedPacket);
        }
        return switch (descriptor.playbackScope()) {
            case VIEWER_LOCAL -> RewriteResult.dropLocal(
                    "packet changes the current live observer's local state");
            case UNSAFE -> RewriteResult.fail("unsafe replay packet reached the rewrite boundary");
            case SAFE -> RewriteResult.send(decodedPacket);
            case REWRITE_IDENTITIES -> RewriteResult.send(
                    rewriteIdentityBearing(decodedPacket, packetName));
        };
    }

    /**
     * Some packet types combine world-visible data with a field that targets
     * the current connection. The descriptor cannot express that distinction,
     * so it is made only after the version-specific payload was decoded.
     */
    private RewriteResult rewritePayloadSensitive(Object packet, String packetName) {
        if ("minecraft:explode".equals(packetName)) {
            ClientboundExplodePacket value = require(packet, ClientboundExplodePacket.class);
            return RewriteResult.send(new ClientboundExplodePacket(
                    value.center(),
                    value.radius(),
                    value.blockCount(),
                    Optional.empty(),
                    value.explosionParticle(),
                    value.explosionSound(),
                    value.blockParticles()));
        }
        if ("minecraft:game_event".equals(packetName)) {
            ClientboundGameEventPacket value = require(packet, ClientboundGameEventPacket.class);
            if (isViewerLocalGameEvent(value.getEvent())) {
                return RewriteResult.dropLocal(
                        "game-event payload changes the current live observer's local state");
            }
        }
        if ("minecraft:player_chat".equals(packetName)) {
            net.minecraft.network.protocol.game.ClientboundPlayerChatPacket value =
                    require(packet, net.minecraft.network.protocol.game.ClientboundPlayerChatPacket.class);
            if (value.sender().equals(context.observerId())) {
                return RewriteResult.dropLocal(
                        "recorded player chat belongs to the live replay observer");
            }
        }
        return null;
    }

    private static boolean isViewerLocalGameEvent(ClientboundGameEventPacket.Type event) {
        return event == ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE
                || event == ClientboundGameEventPacket.CHANGE_GAME_MODE
                || event == ClientboundGameEventPacket.WIN_GAME
                || event == ClientboundGameEventPacket.DEMO_EVENT
                || event == ClientboundGameEventPacket.IMMEDIATE_RESPAWN
                || event == ClientboundGameEventPacket.LIMITED_CRAFTING;
    }

    UUID mapUuid(UUID recordedId) {
        return identities.mapUuid(recordedId);
    }

    int mapEntityId(int recordedId) {
        return identities.mapEntityId(recordedId);
    }

    void clear() {
        identities.clear();
        activeBossBars.clear();
    }

    void resetView() {
        activeBossBars.clear();
    }

    public static boolean isViewerLocal(String identifier) {
        return VIEWER_LOCAL.contains(identifier);
    }

    public static boolean isViewerUi(String identifier) {
        if (identifier.startsWith("play.clientbound.")) {
            identifier = identifier.substring("play.clientbound.".length());
        }
        return identifier.equals("minecraft:set_action_bar_text")
                || identifier.equals("minecraft:set_player_inventory")
                || identifier.equals("minecraft:container_set_content")
                || identifier.equals("minecraft:container_set_data")
                || identifier.equals("minecraft:container_set_slot")
                || identifier.equals("minecraft:set_cursor_item")
                || identifier.equals("minecraft:set_held_slot");
    }

    public static boolean isIdentityBearing(String identifier) {
        return IDENTITY_BEARING.contains(identifier);
    }

    private static IncompatibleAdapterException incompatible(String message) {
        return new IncompatibleAdapterException(message);
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return new IncompatibleAdapterException(message, cause);
    }

    private Object rewriteIdentityBearing(Object packet, String packetName) {
        return switch (packetName) {
            case "minecraft:add_entity" -> {
                ClientboundAddEntityPacket value = require(packet, ClientboundAddEntityPacket.class);
                int data = value.getData();
                // The fishing-bobber spawn packet stores its hook owner as an
                // entity ID. For other entity types data is a type-specific
                // value (for example a block-state ID), so it must remain
                // untouched.
                if ("fishing_hook".equals(EntityType.getKey(value.getType()).getPath()) && data > 0) {
                    data = mapEntityId(data);
                }
                yield new ClientboundAddEntityPacket(
                        mapEntityId(value.getId()),
                        mapUuid(value.getUUID()),
                        value.getX(),
                        value.getY(),
                        value.getZ(),
                        value.getXRot(),
                        value.getYRot(),
                        value.getType(),
                        data,
                        value.getMovement(),
                        value.getYHeadRot());
            }
            case "minecraft:damage_event" -> {
                ClientboundDamageEventPacket value = require(packet, ClientboundDamageEventPacket.class);
                yield new ClientboundDamageEventPacket(
                        mapEntityId(value.entityId()),
                        value.sourceType(),
                        mapOptionalEntityId(value.sourceCauseId()),
                        mapOptionalEntityId(value.sourceDirectId()),
                        value.sourcePosition());
            }
            case "minecraft:entity_position_sync" -> {
                ClientboundEntityPositionSyncPacket value =
                        require(packet, ClientboundEntityPositionSyncPacket.class);
                yield new ClientboundEntityPositionSyncPacket(
                        mapEntityId(value.id()), value.values(), value.onGround());
            }
            case "minecraft:hurt_animation" -> {
                ClientboundHurtAnimationPacket value = require(packet, ClientboundHurtAnimationPacket.class);
                yield new ClientboundHurtAnimationPacket(mapEntityId(value.id()), value.yaw());
            }
            case "minecraft:move_entity_pos" -> {
                ClientboundMoveEntityPacket.Pos value =
                        require(packet, ClientboundMoveEntityPacket.Pos.class);
                yield new ClientboundMoveEntityPacket.Pos(
                        mapEntityId(readIntField(value, "entityId", packetName)),
                        value.getXa(),
                        value.getYa(),
                        value.getZa(),
                        value.isOnGround());
            }
            case "minecraft:move_entity_pos_rot" -> {
                ClientboundMoveEntityPacket.PosRot value =
                        require(packet, ClientboundMoveEntityPacket.PosRot.class);
                yield new ClientboundMoveEntityPacket.PosRot(
                        mapEntityId(readIntField(value, "entityId", packetName)),
                        value.getXa(),
                        value.getYa(),
                        value.getZa(),
                        readByteField(value, "yRot", packetName),
                        readByteField(value, "xRot", packetName),
                        value.isOnGround());
            }
            case "minecraft:move_entity_rot" -> {
                ClientboundMoveEntityPacket.Rot value =
                        require(packet, ClientboundMoveEntityPacket.Rot.class);
                yield new ClientboundMoveEntityPacket.Rot(
                        mapEntityId(readIntField(value, "entityId", packetName)),
                        readByteField(value, "yRot", packetName),
                        readByteField(value, "xRot", packetName),
                        value.isOnGround());
            }
            case "minecraft:rotate_head" -> rewriteHeadRotation(packet, packetName);
            case "minecraft:remove_entities" -> {
                ClientboundRemoveEntitiesPacket value = require(packet, ClientboundRemoveEntitiesPacket.class);
                int[] ids = value.getEntityIds().toIntArray();
                for (int index = 0; index < ids.length; index++) {
                    ids[index] = mapEntityId(ids[index]);
                }
                yield new ClientboundRemoveEntitiesPacket(ids);
            }
            case "minecraft:set_entity_data" -> {
                ClientboundSetEntityDataPacket value = require(packet, ClientboundSetEntityDataPacket.class);
                yield new ClientboundSetEntityDataPacket(
                        mapEntityId(value.id()), rewriteEntityData(value.packedItems()));
            }
            case "minecraft:set_entity_motion" -> {
                ClientboundSetEntityMotionPacket value = require(packet, ClientboundSetEntityMotionPacket.class);
                yield new ClientboundSetEntityMotionPacket(mapEntityId(value.id()), value.movement());
            }
            case "minecraft:set_equipment" -> {
                net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket value =
                        require(packet, net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket.class);
                yield new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                        mapEntityId(value.getEntity()), value.getSlots());
            }
            case "minecraft:set_passengers" -> rewritePassengers(packet);
            case "minecraft:teleport_entity" -> {
                ClientboundTeleportEntityPacket value = require(packet, ClientboundTeleportEntityPacket.class);
                yield new ClientboundTeleportEntityPacket(
                        mapEntityId(value.id()), value.change(), value.relatives(), value.onGround());
            }
            case "minecraft:update_mob_effect" -> {
                ClientboundUpdateMobEffectPacket value =
                        require(packet, ClientboundUpdateMobEffectPacket.class);
                yield new ClientboundUpdateMobEffectPacket(
                        mapEntityId(value.getEntityId()),
                        effectInstance(value),
                        value.isEffectVisible());
            }
            case "minecraft:update_attributes" -> rewriteAttributes(packet);
            case "minecraft:player_info_remove" -> {
                ClientboundPlayerInfoRemovePacket value = require(packet, ClientboundPlayerInfoRemovePacket.class);
                yield new ClientboundPlayerInfoRemovePacket(value.profileIds().stream()
                        .map(this::mapUuid)
                        .toList());
            }
            case "minecraft:player_chat" -> {
                net.minecraft.network.protocol.game.ClientboundPlayerChatPacket value =
                        require(packet, net.minecraft.network.protocol.game.ClientboundPlayerChatPacket.class);
                yield new net.minecraft.network.protocol.game.ClientboundPlayerChatPacket(
                        value.globalIndex(),
                        mapUuid(value.sender()),
                        value.index(),
                        value.signature(),
                        value.body(),
                        value.unsignedContent(),
                        value.filterMask(),
                        value.chatType());
            }
            case "minecraft:player_info_update" -> rewritePlayerInfo(packet);
            case "minecraft:projectile_power" -> {
                net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket value =
                        require(packet, net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket.class);
                yield new net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket(
                        mapEntityId(value.getId()), value.getAccelerationPower());
            }
            case "minecraft:remove_mob_effect" -> {
                net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket value =
                        require(packet, net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket.class);
                yield new net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket(
                        mapEntityId(value.entityId()), value.effect());
            }
            case "minecraft:take_item_entity" -> {
                net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket value =
                        require(packet, net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket.class);
                yield new net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket(
                        mapEntityId(value.getItemId()), mapEntityId(value.getPlayerId()), value.getAmount());
            }
            case "minecraft:set_score" -> {
                net.minecraft.network.protocol.game.ClientboundSetScorePacket value =
                        require(packet, net.minecraft.network.protocol.game.ClientboundSetScorePacket.class);
                yield new net.minecraft.network.protocol.game.ClientboundSetScorePacket(
                        identities.mapName(value.owner()),
                        identities.mapObjectiveName(value.objectiveName()),
                        value.score(),
                        value.display(),
                        value.numberFormat());
            }
            case "minecraft:reset_score" -> {
                net.minecraft.network.protocol.game.ClientboundResetScorePacket value =
                        require(packet, net.minecraft.network.protocol.game.ClientboundResetScorePacket.class);
                yield new net.minecraft.network.protocol.game.ClientboundResetScorePacket(
                        identities.mapName(value.owner()),
                        value.objectiveName() == null
                                ? null
                                : identities.mapObjectiveName(value.objectiveName()));
            }
            default -> throw incompatible(
                    "no explicit Paper 26.2 identity rewrite exists for " + packetName);
        };
    }

    private Object rewriteAttributes(Object packet) {
        ClientboundUpdateAttributesPacket value = require(packet, ClientboundUpdateAttributesPacket.class);
        List<AttributeInstance> attributes = value.getValues().stream()
                .map(snapshot -> {
                    AttributeInstance attribute = new AttributeInstance(
                            snapshot.attribute(), ignored -> {});
                    attribute.setBaseValue(snapshot.base());
                    snapshot.modifiers().forEach(attribute::addOrUpdateTransientModifier);
                    return attribute;
                })
                .toList();
        return new ClientboundUpdateAttributesPacket(mapEntityId(value.getEntityId()), attributes);
    }

    private Object rewritePassengers(Object packet) {
        ClientboundSetPassengersPacket value = require(packet, ClientboundSetPassengersPacket.class);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(mapEntityId(value.getVehicle()));
            int[] passengers = value.getPassengers();
            int[] mappedPassengers = new int[passengers.length];
            for (int index = 0; index < passengers.length; index++) {
                mappedPassengers[index] = mapEntityId(passengers[index]);
            }
            buffer.writeVarIntArray(mappedPassengers);
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
        } catch (RuntimeException | LinkageError failure) {
            throw incompatible("could not rewrite set_passengers packet", failure);
        } finally {
            buffer.release();
        }
    }

    private Object rewriteHeadRotation(Object packet, String packetName) {
        ClientboundRotateHeadPacket value = require(packet, ClientboundRotateHeadPacket.class);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(mapEntityId(readIntField(value, "entityId", packetName)));
            buffer.writeByte(readByteField(value, "yHeadRot", packetName));
            return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buffer);
        } catch (RuntimeException | LinkageError failure) {
            throw incompatible("could not rewrite rotate_head packet", failure);
        } finally {
            buffer.release();
        }
    }

    private Object rewritePlayerInfo(Object packet) {
        ClientboundPlayerInfoUpdatePacket value = require(packet, ClientboundPlayerInfoUpdatePacket.class);
        java.util.List<ClientboundPlayerInfoUpdatePacket.Entry> entries = value.entries().stream()
                .map(entry -> {
                    UUID mappedId = mapUuid(entry.profileId());
                    GameProfile profile = entry.profile() == null
                            ? null
                            : new GameProfile(
                                    mappedId,
                                    identities.mapName(entry.profile().name()),
                                    entry.profile().properties());
                    return new ClientboundPlayerInfoUpdatePacket.Entry(
                            mappedId,
                            profile,
                            entry.listed(),
                            entry.latency(),
                            entry.gameMode(),
                            entry.displayName() == null && entry.profile() != null
                                    ? net.minecraft.network.chat.Component.literal(entry.profile().name())
                                    : entry.displayName(),
                            entry.showHat(),
                            entry.listOrder(),
                            null);
                })
                .toList();
        return new ClientboundPlayerInfoUpdatePacket(value.actions(), entries);
    }

    private RewriteResult rewriteBossEvent(Object packet) {
        ClientboundBossEventPacket value = require(packet, ClientboundBossEventPacket.class);
        AtomicReference<ClientboundBossEventPacket> rewritten = new AtomicReference<>();
        AtomicReference<String> dropReason = new AtomicReference<>();
        value.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(
                    UUID id,
                    net.minecraft.network.chat.Component name,
                    float progress,
                    net.minecraft.world.BossEvent.BossBarColor color,
                    net.minecraft.world.BossEvent.BossBarOverlay overlay,
                    boolean darkenSky,
                    boolean playMusic,
                    boolean createWorldFog) {
                net.minecraft.world.BossEvent event = bossEvent(
                        id, name, progress, color, overlay,
                        darkenSky, playMusic, createWorldFog);
                rewritten.set(ClientboundBossEventPacket.createAddPacket(event));
                activeBossBars.add(id);
            }

            @Override
            public void remove(UUID id) {
                if (activeBossBars.remove(id)) {
                    rewritten.set(ClientboundBossEventPacket.createRemovePacket(mapUuid(id)));
                } else {
                    dropReason.set("boss-bar removal has no replay-visible add packet");
                }
            }

            @Override
            public void updateProgress(UUID id, float progress) {
                if (!activeBossBars.contains(id)) {
                    dropReason.set("boss-bar progress update has no replay-visible add packet");
                    return;
                }
                rewritten.set(ClientboundBossEventPacket.createUpdateProgressPacket(
                        bossEvent(id, net.minecraft.network.chat.Component.empty(), progress,
                                net.minecraft.world.BossEvent.BossBarColor.WHITE,
                                net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS,
                                false, false, false)));
            }

            @Override
            public void updateName(UUID id, net.minecraft.network.chat.Component name) {
                if (!activeBossBars.contains(id)) {
                    dropReason.set("boss-bar name update has no replay-visible add packet");
                    return;
                }
                rewritten.set(ClientboundBossEventPacket.createUpdateNamePacket(
                        bossEvent(id, name, 1.0F,
                                net.minecraft.world.BossEvent.BossBarColor.WHITE,
                                net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS,
                                false, false, false)));
            }

            @Override
            public void updateStyle(
                    UUID id,
                    net.minecraft.world.BossEvent.BossBarColor color,
                    net.minecraft.world.BossEvent.BossBarOverlay overlay) {
                if (!activeBossBars.contains(id)) {
                    dropReason.set("boss-bar style update has no replay-visible add packet");
                    return;
                }
                rewritten.set(ClientboundBossEventPacket.createUpdateStylePacket(
                        bossEvent(id, net.minecraft.network.chat.Component.empty(), 1.0F,
                                color, overlay, false, false, false)));
            }

            @Override
            public void updateProperties(
                    UUID id,
                    boolean darkenSky,
                    boolean playMusic,
                    boolean createWorldFog) {
                if (!activeBossBars.contains(id)) {
                    dropReason.set("boss-bar property update has no replay-visible add packet");
                    return;
                }
                rewritten.set(ClientboundBossEventPacket.createUpdatePropertiesPacket(
                        bossEvent(id, net.minecraft.network.chat.Component.empty(), 1.0F,
                                net.minecraft.world.BossEvent.BossBarColor.WHITE,
                                net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS,
                                darkenSky, playMusic, createWorldFog)));
            }
        });
        ClientboundBossEventPacket result = rewritten.get();
        if (dropReason.get() != null) {
            return RewriteResult.dropLocal(dropReason.get());
        }
        if (result == null) {
            throw incompatible("Paper boss-event packet has an unknown operation", null);
        }
        return RewriteResult.send(result);
    }

    private net.minecraft.world.BossEvent bossEvent(
            UUID id,
            net.minecraft.network.chat.Component name,
            float progress,
            net.minecraft.world.BossEvent.BossBarColor color,
            net.minecraft.world.BossEvent.BossBarOverlay overlay,
            boolean darkenSky,
            boolean playMusic,
            boolean createWorldFog) {
        net.minecraft.world.BossEvent event = new net.minecraft.world.BossEvent(
                mapUuid(id), name, color, overlay) {
        };
        event.setProgress(progress);
        event.setDarkenScreen(darkenSky);
        event.setPlayBossMusic(playMusic);
        event.setCreateWorldFog(createWorldFog);
        return event;
    }

    private List<SynchedEntityData.DataValue<?>> rewriteEntityData(
            List<SynchedEntityData.DataValue<?>> values) {
        Objects.requireNonNull(values, "values");
        List<SynchedEntityData.DataValue<?>> rewritten = new ArrayList<>(values.size());
        for (SynchedEntityData.DataValue<?> value : values) {
            rewritten.add(rewriteEntityDataValue(value));
        }
        return List.copyOf(rewritten);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SynchedEntityData.DataValue<?> rewriteEntityDataValue(
            SynchedEntityData.DataValue<?> value) {
        Objects.requireNonNull(value, "value");
        return new SynchedEntityData.DataValue(
                value.id(), value.serializer(), rewriteMetadataValue(value.value()));
    }

    private Object rewriteMetadataValue(Object value) {
        if (value instanceof UUID uuid) {
            return mapUuid(uuid);
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(this::rewriteMetadataValue);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::rewriteMetadataValue).toList();
        }
        return value;
    }

    private static int readIntField(Object target, String name, String packetName) {
        java.lang.reflect.Field field = findField(target.getClass(), name, packetName);
        try {
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw incompatible("could not safely read " + name + " from " + packetName, failure);
        }
    }

    private static byte readByteField(Object target, String name, String packetName) {
        java.lang.reflect.Field field = findField(target.getClass(), name, packetName);
        try {
            field.setAccessible(true);
            return field.getByte(target);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw incompatible("could not safely read " + name + " from " + packetName, failure);
        }
    }

    private static java.lang.reflect.Field findField(
            Class<?> type,
            String name,
            String packetName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException missing) {
                current = current.getSuperclass();
            }
        }
        throw incompatible("Paper packet has no expected " + name + " field for " + packetName);
    }

    private int mapOptionalEntityId(int id) {
        return id < 0 ? id : mapEntityId(id);
    }

    private static net.minecraft.world.effect.MobEffectInstance effectInstance(
            ClientboundUpdateMobEffectPacket value) {
        return new net.minecraft.world.effect.MobEffectInstance(
                value.getEffect(),
                value.getEffectDurationTicks(),
                value.getEffectAmplifier(),
                value.isEffectAmbient(),
                value.isEffectVisible(),
                value.effectShowsIcon());
    }

    private static <T> T require(Object value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw incompatible("decoded packet type does not match its verified descriptor");
        }
        return type.cast(value);
    }

    /** Result of the version-bound decoded-packet isolation step. */
    record RewriteResult(Decision decision, Object packet, String reason) {
        RewriteResult {
            Objects.requireNonNull(decision, "decision");
            if (decision == Decision.SEND) {
                Objects.requireNonNull(packet, "packet");
            } else if (packet != null) {
                throw new IllegalArgumentException("non-send rewrite result must not carry a packet");
            }
            Objects.requireNonNull(reason, "reason");
        }

        static RewriteResult send(Object packet) {
            return new RewriteResult(Decision.SEND, packet, "send");
        }

        static RewriteResult dropLocal(String reason) {
            return new RewriteResult(Decision.DROP_LOCAL, null, reason);
        }

        static RewriteResult fail(String reason) {
            return new RewriteResult(Decision.FAIL, null, reason);
        }

        enum Decision {
            SEND,
            DROP_LOCAL,
            FAIL
        }
    }
}
