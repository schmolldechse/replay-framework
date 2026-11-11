package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;

/** Version-bound Paper 26.2 entity-state packet construction. */
final class Paper26_2EntityPacketFactory {

    private Paper26_2EntityPacketFactory() {}

    static Packet<?> position(Entity entity, Location location) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(location, "location");
        PositionMoveRotation values = new PositionMoveRotation(
                new Vec3(location.getX(), location.getY(), location.getZ()),
                entity.getDeltaMovement(),
                location.getYaw(),
                location.getPitch());
        return new ClientboundEntityPositionSyncPacket(
                entity.getId(), values, entity.onGround());
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

    static ClientboundAnimatePacket swing(int entityId, InteractionHand hand) {
        Objects.requireNonNull(hand, "hand");
        int action = hand == InteractionHand.OFF_HAND
                ? ClientboundAnimatePacket.SWING_OFF_HAND
                : ClientboundAnimatePacket.SWING_MAIN_HAND;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(action);
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static byte rotationByte(float yaw) {
        return (byte) Math.floor(yaw * 256.0F / 360.0F);
    }
}
