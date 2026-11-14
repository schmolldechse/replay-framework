package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/** Complete read/rewrite/rebuild contract for {@code minecraft:teleport_entity}. */
final class Id125TeleportEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundTeleportEntityPacket,
        Id125TeleportEntityPacketDefinition.Value> {

    static final int PACKET_ID = 0x7D;
    static final String IDENTIFIER = "minecraft:teleport_entity";

    Id125TeleportEntityPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundTeleportEntityPacket.class);
    }

    @Override
    protected Value read(ClientboundTeleportEntityPacket packet) {
        return new Value(packet.id(), packet.change(), packet.relatives(), packet.onGround());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()),
                value.change(),
                value.relatives(),
                value.onGround()));
    }

    @Override
    protected ClientboundTeleportEntityPacket rebuild(Value value) {
        return new ClientboundTeleportEntityPacket(
                value.entityId(), value.change(), value.relatives(), value.onGround());
    }

    /**
     * Position, motion and rotation values stay verbatim because {@code relatives} controls
     * whether each one is interpreted as an absolute value or a delta against prior scene state.
     */
    record Value(
            int entityId,
            PositionMoveRotation change,
            Set<Relative> relatives,
            boolean onGround) {

        Value {
            Objects.requireNonNull(change, "change");
            relatives = Set.copyOf(Objects.requireNonNull(relatives, "relatives"));
            Vec3 position = Objects.requireNonNull(change.position(), "change.position");
            Vec3 deltaMovement = Objects.requireNonNull(
                    change.deltaMovement(), "change.deltaMovement");
            requireFinite(position.x, "position.x");
            requireFinite(position.y, "position.y");
            requireFinite(position.z, "position.z");
            requireFinite(deltaMovement.x, "deltaMovement.x");
            requireFinite(deltaMovement.y, "deltaMovement.y");
            requireFinite(deltaMovement.z, "deltaMovement.z");
            if (!Float.isFinite(change.yRot())) {
                throw new IncompatibleAdapterException(
                        "teleport_entity contains a non-finite yRot value");
            }
            if (!Float.isFinite(change.xRot())) {
                throw new IncompatibleAdapterException(
                        "teleport_entity contains a non-finite xRot value");
            }
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IncompatibleAdapterException(
                    "teleport_entity contains a non-finite " + field + " value");
        }
    }
}
