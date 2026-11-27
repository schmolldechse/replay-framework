package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior.MinecartStep;

/** Complete read/rewrite/rebuild contract for {@code minecraft:move_minecart_along_track}. */
final class Id056MoveMinecartAlongTrackPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundMoveMinecartPacket,
        Id056MoveMinecartAlongTrackPacketDefinition.Value> {

    static final int PACKET_ID = 0x38;
    static final String IDENTIFIER = "minecraft:move_minecart_along_track";

    Id056MoveMinecartAlongTrackPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundMoveMinecartPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.ENTITY_METADATA);
    }

    @Override
    protected Value read(ClientboundMoveMinecartPacket packet) {
        return new Value(packet.entityId(), packet.lerpSteps());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.lerpSteps()));
    }

    @Override
    protected ClientboundMoveMinecartPacket rebuild(Value value) {
        return new ClientboundMoveMinecartPacket(value.entityId(), value.lerpSteps());
    }

    /**
     * The ordered steps describe an interpolated minecart trajectory, so keep
     * each native step intact instead of flattening this into one position.
     */
    record Value(int entityId, List<MinecartStep> lerpSteps) {

        Value {
            Objects.requireNonNull(lerpSteps, "lerpSteps");
            lerpSteps = List.copyOf(lerpSteps);
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
