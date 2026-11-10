package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Paper 26.3 implementation of {@code minecraft:add_entity}. */
public final class AddEntityPacketHandler extends AbstractClientboundPacketHandler<
        ClientboundAddEntityPacket,
        AddEntityPacketHandler.Value> {

    public AddEntityPacketHandler(int packetId) {
        super(new PacketSpec(
                        "play.clientbound.minecraft:add_entity",
                        packetId,
                        PacketSpec.PlaybackPolicy.REWRITE_IDENTITIES,
                        true),
                ClientboundAddEntityPacket.class);
    }

    @Override
    protected Value read(ClientboundAddEntityPacket packet) {
        return new Value(
                packet.getId(),
                packet.getUUID(),
                packet.getX(),
                packet.getY(),
                packet.getZ(),
                packet.getXRot(),
                packet.getYRot(),
                packet.getType(),
                packet.getData(),
                packet.getMovement(),
                packet.getYHeadRot());
    }

    @Override
    protected RewriteDecision<Value> rewrite(Value value, PlaybackContext context) {
        int data = value.data();
        if (isFishingHook(value.entityType()) && data > 0) {
            // Only the fishing-hook variant uses data as an entity reference.
            // Other add-entity variants may use the same integer as unrelated
            // type-specific data and must remain untouched.
            data = context.mapEntityId(data);
        }
        return RewriteDecision.send(new Value(
                context.mapEntityId(value.entityId()),
                context.mapUuid(value.entityUuid()),
                value.x(),
                value.y(),
                value.z(),
                value.xRot(),
                value.yRot(),
                value.entityType(),
                data,
                value.movement(),
                value.yHeadRot()));
    }

    @Override
    protected ClientboundAddEntityPacket rebuild(Value value) {
        return new ClientboundAddEntityPacket(
                value.entityId(),
                value.entityUuid(),
                value.x(),
                value.y(),
                value.z(),
                value.xRot(),
                value.yRot(),
                value.entityType(),
                value.data(),
                value.movement(),
                value.yHeadRot());
    }

    private static boolean isFishingHook(EntityType<?> entityType) {
        String path = EntityType.getKey(entityType).getPath();
        // Keep the check explicit and version-local. The second name makes the
        // prototype readable across the terminology used by adjacent mappings;
        // productive code should assert the exact 26.3 registry identifier.
        return path.equals("fishing_hook") || path.equals("fishing_bobber");
    }

    /** Immutable semantic value read from and rebuilt into the native packet. */
    public record Value(
            int entityId,
            UUID entityUuid,
            double x,
            double y,
            double z,
            float xRot,
            float yRot,
            EntityType<?> entityType,
            int data,
            Vec3 movement,
            double yHeadRot) {

        public Value {
            if (entityId < 0) {
                throw new IllegalArgumentException("entityId must not be negative");
            }
            Objects.requireNonNull(entityUuid, "entityUuid");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(movement, "movement");
        }
    }
}
