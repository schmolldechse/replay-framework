package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2NativePacketCodec;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.kyori.adventure.key.Key;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Coalesces serverbound Paper 26.2 player packets into authoritative main-thread
 * movement, equipment and arm-swing state.
 */
final class Paper26_2PlayerStateCapture implements AutoCloseable {

    private final JavaPlugin owner;
    private final Paper26_2NativePacketCodec codec;
    private final BooleanSupplier active;
    private final LongSupplier serverTick;
    private final Consumer<Paper26_2SyntheticEntityState> emitter;
    private final Consumer<Throwable> failureHandler;
    private final Paper26_2AuthoritativeStateIndex stateIndex;
    private final Map<UUID, PendingSample> pending = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    Paper26_2PlayerStateCapture(
            JavaPlugin owner,
            Paper26_2NativePacketCodec codec,
            BooleanSupplier active,
            LongSupplier serverTick,
            Consumer<Paper26_2SyntheticEntityState> emitter,
            Consumer<Throwable> failureHandler,
            Paper26_2AuthoritativeStateIndex stateIndex) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.active = Objects.requireNonNull(active, "active");
        this.serverTick = Objects.requireNonNull(serverTick, "serverTick");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.stateIndex = Objects.requireNonNull(stateIndex, "stateIndex");
    }

    /** Accepts one verified PLAY serverbound packet without reading world state off-thread. */
    void capture(PacketReceiveEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed.get() || !active.getAsBoolean() || event.isCancelled()) {
            return;
        }

        UUID playerId = event.getUser().getUUID();
        if (playerId == null) {
            return;
        }

        try {
            int entityId = event.getUser().getEntityId();
            stateIndex.markMovement(entityId);
            stateIndex.markEquipment(entityId);

            InteractionHand swing = null;
            if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
                swing = new WrapperPlayClientAnimation(event).getHand();
                stateIndex.markSwing(entityId, serverTick.getAsLong());
            }
            requestSample(playerId, swing);
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }

    private void requestSample(UUID playerId, InteractionHand swing) {
        AtomicBoolean schedule = new AtomicBoolean();
        pending.compute(playerId, (ignored, current) -> {
            PendingSample result = current;
            if (result == null) {
                result = new PendingSample();
                schedule.set(true);
            }
            if (swing != null) {
                result.swings().add(swing);
            }
            return result;
        });
        if (!schedule.get()) {
            return;
        }
        try {
            owner.getServer().getScheduler().runTask(owner, () -> drain(playerId));
        } catch (Throwable failure) {
            pending.remove(playerId);
            failureHandler.accept(failure);
        }
    }

    private void drain(UUID playerId) {
        PendingSample request = pending.remove(playerId);
        if (request == null || closed.get() || !active.getAsBoolean()) {
            return;
        }
        Player player = owner.getServer().getPlayer(playerId);
        if (!(player instanceof CraftPlayer craftPlayer) || !player.isOnline()) {
            snapshots.remove(playerId);
            return;
        }
        try {
            captureState(craftPlayer, List.copyOf(request.swings()));
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }

    private void captureState(CraftPlayer player, List<InteractionHand> swings) {
        net.minecraft.server.level.ServerPlayer handle = player.getHandle();
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        PlayerSnapshot previous = snapshots.get(playerId);
        Key world = location.getWorld().getKey();
        boolean identityChanged = previous == null || previous.entityId() != handle.getId();
        boolean movementChanged = identityChanged
                || !previous.world().equals(world)
                || previous.x() != location.getX()
                || previous.y() != location.getY()
                || previous.z() != location.getZ()
                || previous.yaw() != location.getYaw()
                || previous.pitch() != location.getPitch()
                || previous.onGround() != handle.onGround();

        if (movementChanged) {
            emit(location, Paper26_2EntityPacketFactory.position(handle, location));
            if (identityChanged || previous.yaw() != location.getYaw()) {
                emit(location, Paper26_2EntityPacketFactory.headRotation(handle, location.getYaw()));
            }
        }

        Map<EquipmentSlot, ItemStack> currentEquipment = equipment(handle);
        Map<EquipmentSlot, ItemStack> changedEquipment = changedEquipment(
                identityChanged ? null : previous.equipment(), currentEquipment);
        if (!changedEquipment.isEmpty()) {
            emit(location, Paper26_2EntityPacketFactory.equipment(
                    handle.getId(), changedEquipment));
        }

        for (InteractionHand swing : swings) {
            emit(location, Paper26_2EntityPacketFactory.swing(handle.getId(), swing));
        }

        snapshots.put(playerId, new PlayerSnapshot(
                handle.getId(), world,
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), handle.onGround(),
                copyEquipment(currentEquipment)));
    }

    private void emit(Location location, net.minecraft.network.protocol.Packet<?> packet) {
        emitter.accept(new Paper26_2SyntheticEntityState(
                codec.encodeState(packet),
                location.getWorld().getKey(),
                new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ())));
    }

    static Map<EquipmentSlot, ItemStack> changedEquipment(
            Map<EquipmentSlot, ItemStack> previous,
            Map<EquipmentSlot, ItemStack> current) {
        Objects.requireNonNull(current, "current");
        Map<EquipmentSlot, ItemStack> changed = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            ItemStack currentItem = Objects.requireNonNull(current.get(slot), "current equipment slot");
            ItemStack previousItem = previous == null ? null : previous.get(slot);
            if (previousItem == null || !ItemStack.matches(previousItem, currentItem)) {
                changed.put(slot, currentItem.copy());
            }
        }
        return changed;
    }

    private static Map<EquipmentSlot, ItemStack> equipment(
            net.minecraft.world.entity.LivingEntity entity) {
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            equipment.put(slot, entity.getItemBySlot(slot).copy());
        }
        return equipment;
    }

    private static Map<EquipmentSlot, ItemStack> copyEquipment(
            Map<EquipmentSlot, ItemStack> source) {
        Map<EquipmentSlot, ItemStack> copy = new EnumMap<>(EquipmentSlot.class);
        source.forEach((slot, item) -> copy.put(slot, item.copy()));
        return copy;
    }

    void remove(UUID playerId) {
        pending.remove(playerId);
        snapshots.remove(playerId);
    }

    @Override
    public void close() {
        closed.set(true);
        pending.clear();
        snapshots.clear();
    }

    private record PendingSample(ConcurrentLinkedQueue<InteractionHand> swings) {
        private PendingSample() {
            this(new ConcurrentLinkedQueue<>());
        }
    }

    private record PlayerSnapshot(
            int entityId,
            Key world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            boolean onGround,
            Map<EquipmentSlot, ItemStack> equipment) {

        private PlayerSnapshot {
            Objects.requireNonNull(world, "world");
            equipment = copyEquipment(Objects.requireNonNull(equipment, "equipment"));
        }
    }
}
