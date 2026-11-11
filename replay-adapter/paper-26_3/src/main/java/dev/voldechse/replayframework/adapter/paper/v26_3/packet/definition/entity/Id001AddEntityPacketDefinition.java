package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.entity;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

/** Complete read/rewrite/rebuild contract for {@code minecraft:add_entity}. */
final class Id001AddEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundAddEntityPacket,
        Id001AddEntityPacketDefinition.Value> {

    static final int PACKET_ID = 0x01;
    static final String IDENTIFIER = "minecraft:add_entity";
    private static final Set<EntityType<?>> ENTITY_REFERENCE_DATA_TYPES = Set.of(
            EntityTypes.ARROW,
            EntityTypes.BREEZE_WIND_CHARGE,
            EntityTypes.DRAGON_FIREBALL,
            EntityTypes.EGG,
            EntityTypes.ENDER_PEARL,
            EntityTypes.EXPERIENCE_BOTTLE,
            EntityTypes.FIREBALL,
            EntityTypes.FIREWORK_ROCKET,
            EntityTypes.FISHING_BOBBER,
            EntityTypes.LINGERING_POTION,
            EntityTypes.LLAMA_SPIT,
            EntityTypes.SHULKER_BULLET,
            EntityTypes.SMALL_FIREBALL,
            EntityTypes.SNOWBALL,
            EntityTypes.SPECTRAL_ARROW,
            EntityTypes.SPLASH_POTION,
            EntityTypes.TRIDENT,
            EntityTypes.WIND_CHARGE,
            EntityTypes.WITHER_SKULL);

    Id001AddEntityPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundAddEntityPacket.class);
    }

    @Override
    protected Value read(ClientboundAddEntityPacket packet) {
        return new Value(
                packet.getId(),
                packet.getUUID(),
                packet.getType(),
                packet.getX(),
                packet.getY(),
                packet.getZ(),
                packet.getMovement(),
                packet.getXRot(),
                packet.getYRot(),
                packet.getYHeadRot(),
                packet.getData());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        int data = value.data();
        // `data` has no universal meaning: Projectile#getAddEntityPacket writes
        // its owner/shooter entity ID, but falling blocks use the same integer
        // for a block-state ID and item frames use it for their direction.
        // Rewriting every non-zero value would corrupt those unrelated spawns.
        // EntityType does not expose its concrete NMS class in these builds, so
        // the source-reviewed projectile types are intentionally explicit.
        if (data != 0 && carriesEntityReference(value.entityType())) {
            data = context.mapEntityId(data);
        }
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()),
                context.mapUuid(value.entityUuid()),
                value.entityType(),
                value.x(),
                value.y(),
                value.z(),
                value.movement(),
                value.xRot(),
                value.yRot(),
                value.yHeadRot(),
                data));
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

    record Value(
            int entityId,
            UUID entityUuid,
            EntityType<?> entityType,
            double x,
            double y,
            double z,
            Vec3 movement,
            float xRot,
            float yRot,
            float yHeadRot,
            int data) {

        Value {
            Objects.requireNonNull(entityUuid, "entityUuid");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(movement, "movement");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(movement.x, "movement.x");
            requireFinite(movement.y, "movement.y");
            requireFinite(movement.z, "movement.z");
            requireFinite(xRot, "xRot");
            requireFinite(yRot, "yRot");
            requireFinite(yHeadRot, "yHeadRot");
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IncompatibleAdapterException(
                    "add_entity contains a non-finite " + field + " value");
        }
    }

    private static boolean carriesEntityReference(EntityType<?> entityType) {
        return ENTITY_REFERENCE_DATA_TYPES.contains(entityType);
    }
}
