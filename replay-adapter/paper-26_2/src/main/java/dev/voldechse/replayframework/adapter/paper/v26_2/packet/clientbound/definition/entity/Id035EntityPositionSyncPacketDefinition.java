package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2CaptureContexts;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:entity_position_sync}.
 *
 * <p>The packet carries an absolute entity position, its movement vector,
 * rotation and on-ground state. There are no relative-coordinate flags, so
 * preserve every finite numeric value as sent and only rewrite the entity ID.
 * Non-finite vectors or rotations are rejected rather than propagated into
 * the observer's world state.</p>
 */
final class Id035EntityPositionSyncPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundEntityPositionSyncPacket,
        Id035EntityPositionSyncPacketDefinition.Value> {

    static final int PACKET_ID = 0x23;
    static final String IDENTIFIER = "minecraft:entity_position_sync";

    Id035EntityPositionSyncPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundEntityPositionSyncPacket.class);
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
        Value value = read(ClientboundEntityPositionSyncPacket.class.cast(decodedPacket));
        Optional<String> world = state.entityPosition(connectionId, value.entityId())
                .map(PacketCaptureState.EntityPosition::world)
                .or(() -> state.world(connectionId))
                .or(() -> base.world().map(Key::toString));
        world.ifPresent(key -> state.entityPosition(
                connectionId, value.entityId(), key,
                value.values().position().x,
                value.values().position().y,
                value.values().position().z));
        return world.<CaptureContext>map(key -> Paper26_2CaptureContexts.atPosition(
                        base, Key.key(key),
                        value.values().position().x,
                        value.values().position().y,
                        value.values().position().z))
                .orElse(base);
    }

    @Override
    protected Value read(ClientboundEntityPositionSyncPacket packet) {
        return new Value(packet.id(), packet.values(), packet.onGround());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.values(), value.onGround()));
    }

    @Override
    protected ClientboundEntityPositionSyncPacket rebuild(Value value) {
        return new ClientboundEntityPositionSyncPacket(
                value.entityId(), value.values(), value.onGround());
    }

    record Value(int entityId, PositionMoveRotation values, boolean onGround) {

        Value {
            Objects.requireNonNull(values, "values");
            Vec3 position = Objects.requireNonNull(values.position(), "values.position");
            Vec3 deltaMovement = Objects.requireNonNull(
                    values.deltaMovement(), "values.deltaMovement");
            requireFinite(position.x, "position.x");
            requireFinite(position.y, "position.y");
            requireFinite(position.z, "position.z");
            requireFinite(deltaMovement.x, "deltaMovement.x");
            requireFinite(deltaMovement.y, "deltaMovement.y");
            requireFinite(deltaMovement.z, "deltaMovement.z");
            requireFinite(values.yRot(), "yRot");
            requireFinite(values.xRot(), "xRot");
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IncompatibleAdapterException(
                    "entity_position_sync contains a non-finite " + field + " value");
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
