package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;

/** Complete read/rewrite/rebuild contract for {@code minecraft:add_entity}. */
final class Id001AddEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundAddEntityPacket,
        Id001AddEntityPacketDefinition.Value> {

    static final int PACKET_ID = 0x01;
    static final String IDENTIFIER = "minecraft:add_entity";
    private static final Set<EntityType<?>> OWNER_ENTITY_ID_DATA_TYPES = Set.of(
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
            Paper26_2ReplayPacketRewriter context) {
        context.trackEntityType(value.entityId(), value.entityType());
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
                rewriteData(value, context)));
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

    private static int rewriteData(Value value, Paper26_2ReplayPacketRewriter context) {
        // `data` is a type-discriminated union, not a universal entity ID.
        // Item frames and paintings encode their direction, falling blocks a
        // block-state ID, and wardens use 1 for the emerging pose. Projectiles
        // encode an owner entity ID, with 0 meaning no owner; fishing hooks use
        // their own entity ID when no owner exists. Only those non-zero owner
        // references may enter the identity map, otherwise unrelated values or
        // the projectile "no owner" sentinel would be corrupted.
        if (value.data() == 0 || !carriesOwnerEntityId(value.entityType())) {
            return value.data();
        }
        return context.mapEntityId(value.data());
    }

    private static boolean carriesOwnerEntityId(EntityType<?> entityType) {
        // EntityType#getBaseClass() returns Entity.class in this build, so the
        // source-reviewed projectile types must remain explicit.
        return OWNER_ENTITY_ID_DATA_TYPES.contains(entityType);
    }
}
