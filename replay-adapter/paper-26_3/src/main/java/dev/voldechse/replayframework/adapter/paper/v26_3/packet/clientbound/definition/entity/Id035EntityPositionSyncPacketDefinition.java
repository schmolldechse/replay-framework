package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.entity.PositionPath;
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
        Paper26_3ReplayPacketRewriter,
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
                value.path().endPosition().x,
                value.path().endPosition().y,
                value.path().endPosition().z));
        return world.<CaptureContext>map(key -> CaptureContexts.atPosition(
                        base, Key.key(key),
                        value.path().endPosition().x,
                        value.path().endPosition().y,
                        value.path().endPosition().z))
                .orElse(base);
    }

    @Override
    protected Value read(ClientboundEntityPositionSyncPacket packet) {
        return new Value(
                packet.id(), packet.position(), packet.yRot(), packet.xRot(), packet.onGround());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()),
                value.path(),
                value.yRot(),
                value.xRot(),
                value.onGround()));
    }

    @Override
    protected ClientboundEntityPositionSyncPacket rebuild(Value value) {
        return new ClientboundEntityPositionSyncPacket(
                value.entityId(), value.path(), value.yRot(), value.xRot(), value.onGround());
    }

    /**
     * Paper 26.3 carries a position path (one position, or steps with tick
     * offsets) and the rotations; it no longer carries a delta movement.
     */
    record Value(int entityId, PositionPath path, float yRot, float xRot, boolean onGround) {

        Value {
            Objects.requireNonNull(path, "path");
            Vec3 position = Objects.requireNonNull(path.endPosition(), "path.endPosition");
            requireFinite(position.x, "position.x");
            requireFinite(position.y, "position.y");
            requireFinite(position.z, "position.z");
            requireFinite(yRot, "yRot");
            requireFinite(xRot, "xRot");
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
