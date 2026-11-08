package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint.Paper26_3NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftEquipmentSlot;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Coordinates packet-driven player state with event fallbacks for non-player
 * entities that have no serverbound packet source.
 */
final class Paper26_3EntityStateCapture implements Listener, AutoCloseable {

    private final JavaPlugin owner;
    private final Paper26_3NativePacketCodec codec;
    private final BooleanSupplier active;
    private final Consumer<Paper26_3SyntheticEntityState> emitter;
    private final Consumer<Throwable> failureHandler;
    private final Paper26_3AuthoritativeStateIndex stateIndex =
            new Paper26_3AuthoritativeStateIndex();
    private final Paper26_3PlayerStateCapture playerStateCapture;
    private final AtomicBoolean installed = new AtomicBoolean();

    Paper26_3EntityStateCapture(
            JavaPlugin owner,
            Paper26_3NativePacketCodec codec,
            BooleanSupplier active,
            LongSupplier serverTick,
            Consumer<Paper26_3SyntheticEntityState> emitter,
            Consumer<Throwable> failureHandler) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.active = Objects.requireNonNull(active, "active");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.playerStateCapture = new Paper26_3PlayerStateCapture(
                owner, codec, active, serverTick, emitter, failureHandler, stateIndex);
    }

    void install() {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        owner.getServer().getPluginManager().registerEvents(this, owner);
    }

    void captureClientPacket(PacketReceiveEvent event) {
        if (installed.get()) {
            playerStateCapture.capture(event);
        }
    }

    /** Fallback for non-player movement, which has no serverbound player packet. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) {
        if (event.getEntity() instanceof Player
                || (!event.hasChangedPosition() && !event.hasChangedOrientation())) {
            return;
        }
        captureMovement(event.getEntity(), event.getFrom(), event.getTo());
    }

    /** Fallback for non-player equipment, which has no owner connection to sample. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (event.getEntity() instanceof Player || !capturing()
                || !(event.getEntity() instanceof CraftLivingEntity craftEntity)) {
            return;
        }
        Location location = event.getEntity().getLocation();
        if (location.getWorld() == null || event.getEquipmentChanges().isEmpty()) {
            return;
        }
        try {
            Map<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack> changed =
                    new EnumMap<>(net.minecraft.world.entity.EquipmentSlot.class);
            event.getEquipmentChanges().forEach((slot, change) -> changed.put(
                    CraftEquipmentSlot.getNMS(slot),
                    CraftItemStack.asNMSCopy(change.newItem())));
            int entityId = craftEntity.getHandle().getId();
            stateIndex.markEquipment(entityId);
            emit(location, Paper26_3EntityPacketFactory.equipment(entityId, changed));
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoved(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CraftEntity craftEntity) {
            stateIndex.remove(craftEntity.getHandle().getId());
        }
        if (event.getEntity() instanceof Player player) {
            playerStateCapture.remove(player.getUniqueId());
        }
    }

    boolean suppressesRecipientState(
            String descriptorTypeName,
            byte[] payload,
            long observedServerTick) {
        return stateIndex.suppresses(descriptorTypeName, payload, observedServerTick);
    }

    private void captureMovement(
            org.bukkit.entity.Entity entity,
            Location from,
            Location to) {
        if (!capturing() || to == null || to.getWorld() == null
                || !(entity instanceof CraftEntity craftEntity)) {
            return;
        }
        try {
            int entityId = craftEntity.getHandle().getId();
            stateIndex.markMovement(entityId);
            emit(to, Paper26_3EntityPacketFactory.position(craftEntity.getHandle(), to));
            if (from == null || from.getYaw() != to.getYaw()) {
                emit(to, Paper26_3EntityPacketFactory.headRotation(
                        craftEntity.getHandle(), to.getYaw()));
            }
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }

    private boolean capturing() {
        return installed.get() && active.getAsBoolean();
    }

    private void emit(Location location, net.minecraft.network.protocol.Packet<?> packet) {
        emitter.accept(new Paper26_3SyntheticEntityState(
                codec.encodeState(packet),
                location.getWorld().getKey(),
                new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ())));
    }

    @Override
    public void close() {
        if (installed.compareAndSet(true, false)) {
            HandlerList.unregisterAll(this);
        }
        playerStateCapture.close();
        stateIndex.clear();
    }
}
