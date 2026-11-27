package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:remove_entities}. */
final class Id078RemoveEntitiesPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundRemoveEntitiesPacket,
        Id078RemoveEntitiesPacketDefinition.Value> {

    static final int PACKET_ID = 0x4E;
    static final String IDENTIFIER = "minecraft:remove_entities";

    Id078RemoveEntitiesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundRemoveEntitiesPacket.class);
    }


    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ClientboundRemoveEntitiesPacket packet = ClientboundRemoveEntitiesPacket.class.cast(decodedPacket);
        packet.entityIds().intStream().forEach(state::removeEntity);
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.ENTITY_METADATA);
    }

    @Override
    protected Value read(ClientboundRemoveEntitiesPacket packet) {
        return new Value(packet.entityIds().intStream().boxed().toList());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        List<Integer> rewrittenIds = value.entityIds().stream()
                .map(context::mapEntityId)
                .toList();
        value.entityIds().forEach(context::forgetEntityType);
        return ValueRewriteResult.send(new Value(rewrittenIds));
    }

    @Override
    protected ClientboundRemoveEntitiesPacket rebuild(Value value) {
        return new ClientboundRemoveEntitiesPacket(
                value.entityIds().stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Every entry targets an entity to remove; the list has no absent-ID
     * sentinel, so even unusual signed values are passed through identity
     * mapping. Keep entry order and duplicates as recorded.
     */
    record Value(List<Integer> entityIds) {

        Value {
            Objects.requireNonNull(entityIds, "entityIds");
            entityIds = List.copyOf(entityIds);
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_MEMBERSHIP;
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.SceneMembership sceneMembership(Object decodedPacket) {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.SceneMembership.entitiesLost(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket.class.cast(decodedPacket).entityIds().toIntArray());
    }
}
