package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/** Complete read/rewrite/rebuild implementation for {@code minecraft:add_entity}. */
public final class AddEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundAddEntityPacket,
        AddEntityPacketDefinition.Value> {

    AddEntityPacketDefinition(PacketClassification classification) {
        super("minecraft:add_entity", classification, ClientboundAddEntityPacket.class);
    }

    @Override
    protected Value read(ClientboundAddEntityPacket packet) {
        return new Value(
                packet.getId(), packet.getUUID(), packet.getX(), packet.getY(), packet.getZ(),
                packet.getXRot(), packet.getYRot(), packet.getType(), packet.getData(),
                packet.getMovement(), packet.getYHeadRot());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        int data = value.data();
        if ("fishing_hook".equals(EntityType.getKey(value.entityType()).getPath()) && data > 0) {
            data = context.mapEntityId(data);
        }
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()),
                context.mapUuid(value.entityUuid()),
                value.x(), value.y(), value.z(), value.xRot(), value.yRot(), value.entityType(),
                data, value.movement(), value.yHeadRot()));
    }

    @Override
    protected ClientboundAddEntityPacket rebuild(Value value) {
        return new ClientboundAddEntityPacket(
                value.entityId(), value.entityUuid(), value.x(), value.y(), value.z(),
                value.xRot(), value.yRot(), value.entityType(), value.data(),
                value.movement(), value.yHeadRot());
    }

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
            Objects.requireNonNull(entityUuid, "entityUuid");
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(movement, "movement");
        }
    }
}
