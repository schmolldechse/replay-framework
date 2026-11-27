package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Complete read/rewrite/rebuild contract for {@code minecraft:teleport_entity}. */
final class Id128TeleportEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundTeleportEntityPacket,
        Id128TeleportEntityPacketDefinition.Value> {

    static final int PACKET_ID = 0x80;
    static final String IDENTIFIER = "minecraft:teleport_entity";

    Id128TeleportEntityPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundTeleportEntityPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.ENTITY_METADATA);
    }


    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        Value value = read(ClientboundTeleportEntityPacket.class.cast(decodedPacket));
        Vec3 position = value.change().position();
        var previous = state.entityPosition(connectionId, value.entityId()).orElse(null);
        boolean relativeX = value.relatives().contains(Relative.X);
        boolean relativeY = value.relatives().contains(Relative.Y);
        boolean relativeZ = value.relatives().contains(Relative.Z);
        if (previous == null && (relativeX || relativeY || relativeZ)) {
            return base;
        }
        double x = relativeX ? previous.x() + position.x : position.x;
        double y = relativeY ? previous.y() + position.y : position.y;
        double z = relativeZ ? previous.z() + position.z : position.z;
        var world = previous == null
                ? state.world(connectionId).orElseGet(() -> base.world().map(Key::toString).orElse(null))
                : previous.world();
        if (world == null) {
            return base;
        }
        state.entityPosition(connectionId, value.entityId(), world, x, y, z);
        return CaptureContexts.atPosition(base, Key.key(world), x, y, z);
    }

    @Override
    protected Value read(ClientboundTeleportEntityPacket packet) {
        return new Value(packet.id(), packet.change(), packet.relatives(), packet.onGround());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
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

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
