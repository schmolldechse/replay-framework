package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.mojang.datafixers.util.Pair;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;

/** Version-bound Paper 26.3 entity-state packet construction. */
final class Paper26_3EntityPacketFactory {

    private Paper26_3EntityPacketFactory() {}

    static Packet<?> position(Entity entity, Location location) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(location, "location");
        return new ClientboundEntityPositionSyncPacket(
                entity.getId(),
                PositionPath.of(new Vec3(location.getX(), location.getY(), location.getZ())),
                location.getYaw(),
                location.getPitch(),
                entity.onGround());
    }

    static Packet<?> headRotation(Entity entity, float yaw) {
        Objects.requireNonNull(entity, "entity");
        return new ClientboundRotateHeadPacket(entity, rotationByte(yaw));
    }

    static ClientboundSetEquipmentPacket equipment(
            int entityId,
            Map<EquipmentSlot, ItemStack> changedItems) {
        Objects.requireNonNull(changedItems, "changedItems");
        List<Pair<EquipmentSlot, ItemStack>> slots = changedItems.entrySet().stream()
                .map(entry -> Pair.of(entry.getKey(), entry.getValue().copy()))
                .toList();
        return new ClientboundSetEquipmentPacket(entityId, slots);
    }

    static ClientboundSwingAnimationPacket swing(
            LivingEntity entity,
            InteractionHand packetHand) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(packetHand, "packetHand");
        net.minecraft.world.InteractionHand hand = packetHand == InteractionHand.OFF_HAND
                ? net.minecraft.world.InteractionHand.OFF_HAND
                : net.minecraft.world.InteractionHand.MAIN_HAND;
        return new ClientboundSwingAnimationPacket(
                entity.getId(), hand, entity.getItemInHand(hand).getAttackAnimation());
    }

    private static byte rotationByte(float yaw) {
        return (byte) Math.floor(yaw * 256.0F / 360.0F);
    }
}
