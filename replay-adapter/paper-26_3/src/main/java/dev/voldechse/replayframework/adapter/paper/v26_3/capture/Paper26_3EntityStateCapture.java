package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Captures authoritative Paper 26.3 player and living-entity movement. */
final class Paper26_3EntityStateCapture implements Listener, AutoCloseable {

    private static final Set<String> AUTHORITATIVE_MOVEMENT = Set.of(
            "play.clientbound.minecraft:entity_position_sync",
            "play.clientbound.minecraft:move_entity_pos",
            "play.clientbound.minecraft:move_entity_pos_rot",
            "play.clientbound.minecraft:move_entity_rot",
            "play.clientbound.minecraft:rotate_head",
            "play.clientbound.minecraft:teleport_entity");

    private final JavaPlugin owner;
    private final Paper26_3NativePacketCodec codec;
    private final BooleanSupplier active;
    private final Consumer<SyntheticMovement> emitter;
    private final Consumer<Throwable> failureHandler;
    private final AuthoritativeMovementIndex movementIndex = new AuthoritativeMovementIndex();
    private final AtomicBoolean installed = new AtomicBoolean();

    Paper26_3EntityStateCapture(
            JavaPlugin owner,
            Paper26_3NativePacketCodec codec,
            BooleanSupplier active,
            Consumer<SyntheticMovement> emitter,
            Consumer<Throwable> failureHandler) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.active = Objects.requireNonNull(active, "active");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void install() {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        owner.getServer().getPluginManager().registerEvents(this, owner);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        capture(event.getPlayer(), event.getFrom(), event.getTo(),
                event.hasChangedPosition() || event.hasChangedOrientation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) {
        capture(event.getEntity(), event.getFrom(), event.getTo(),
                event.hasChangedPosition() || event.hasChangedOrientation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CraftEntity craftEntity) {
            movementIndex.remove(craftEntity.getHandle().getId());
        }
    }

    boolean suppressesRecipientMovement(String descriptorTypeName, byte[] payload) {
        Objects.requireNonNull(descriptorTypeName, "descriptorTypeName");
        Objects.requireNonNull(payload, "payload");
        return movementIndex.suppresses(descriptorTypeName, payload);
    }

    void markAuthoritative(int entityId) {
        movementIndex.mark(entityId);
    }

    private void capture(Entity entity, Location from, Location to, boolean changed) {
        if (!installed.get() || !active.getAsBoolean() || !changed || to == null
                || to.getWorld() == null || !(entity instanceof CraftEntity craftEntity)) {
            return;
        }
        try {
            net.minecraft.world.entity.Entity handle = craftEntity.getHandle();
            int entityId = handle.getId();
            markAuthoritative(entityId);
            Key world = to.getWorld().getKey();
            BlockPosition position = new BlockPosition(
                    to.getBlockX(), to.getBlockY(), to.getBlockZ());
            emit(world, position,
                    new ClientboundEntityPositionSyncPacket(
                            entityId,
                            PositionPath.of(new Vec3(to.getX(), to.getY(), to.getZ())),
                            to.getYaw(),
                            to.getPitch(),
                            handle.onGround()));
            if (from == null || from.getYaw() != to.getYaw()) {
                emit(world, position,
                        new ClientboundRotateHeadPacket(handle, rotationByte(to.getYaw())));
            }
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }

    private void emit(
            Key world,
            BlockPosition position,
            net.minecraft.network.protocol.Packet<?> packet) {
        emitter.accept(new SyntheticMovement(
                codec.encodeState(packet), world, position));
    }

    @Override
    public void close() {
        if (installed.compareAndSet(true, false)) {
            HandlerList.unregisterAll(this);
        }
        movementIndex.clear();
    }

    private static byte rotationByte(float yaw) {
        return (byte) Math.floor(yaw * 256.0F / 360.0F);
    }

    static final class AuthoritativeMovementIndex {
        private final Set<Integer> entityIds = ConcurrentHashMap.newKeySet();

        void mark(int entityId) {
            entityIds.add(entityId);
        }

        void remove(int entityId) {
            entityIds.remove(entityId);
        }

        boolean suppresses(String descriptorTypeName, byte[] payload) {
            if (!AUTHORITATIVE_MOVEMENT.contains(descriptorTypeName)) {
                return false;
            }
            return entityIds.contains(readVarInt(payload));
        }

        void clear() {
            entityIds.clear();
        }

        private static int readVarInt(byte[] payload) {
            int value = 0;
            int shift = 0;
            for (byte element : payload) {
                int current = element & 0xFF;
                value |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift >= 35) {
                    break;
                }
            }
            throw new IllegalArgumentException("movement payload has no valid entity ID");
        }
    }

    record SyntheticMovement(
            Paper26_3NativePacketCodec.EncodedStatePacket packet,
            Key world,
            BlockPosition position) {

        SyntheticMovement {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(position, "position");
        }
    }
}
