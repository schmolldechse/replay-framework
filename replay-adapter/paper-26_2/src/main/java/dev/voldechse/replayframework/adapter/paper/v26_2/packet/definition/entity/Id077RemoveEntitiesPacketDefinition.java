package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.packet.ViewTeardownState;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:remove_entities}. */
final class Id077RemoveEntitiesPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundRemoveEntitiesPacket,
        Id077RemoveEntitiesPacketDefinition.Value> {

    static final int PACKET_ID = 0x4D;
    static final String IDENTIFIER = "minecraft:remove_entities";

    Id077RemoveEntitiesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundRemoveEntitiesPacket.class);
    }


    @Override
    public boolean deduplicateRecipientCopies() {
        return true;
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        ClientboundRemoveEntitiesPacket packet = ClientboundRemoveEntitiesPacket.class.cast(decodedPacket);
        ViewTeardownState teardown = state.viewTeardown();
        if (teardown.tearingDown(connectionId)) {
            // The server is untracking every entity from a dead player's
            // view. The entities still exist, so their capture state stays.
            packet.getEntityIds().intStream().forEach(entityId -> teardown.withholdEntityRemoval(
                    connectionId,
                    entityId,
                    state.entityPosition(connectionId, entityId)
                            .map(position -> ViewTeardownState.ChunkCoordinate.containing(
                                    position.x(), position.z()))));
        } else {
            packet.getEntityIds().intStream().forEach(state::removeEntity);
        }
        return super.captureContext(decodedPacket, descriptor, connectionId, state, serverTick);
    }

    /**
     * Removals that only tear down a dead player's view are withheld; the
     * teardown state releases those the respawned view does not bring back.
     */
    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        return state.viewTeardown().tearingDown(connectionId)
                || super.suppressesRecipientState(decodedPacket, connectionId, state, serverTick);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily.ENTITY_METADATA);
    }

    @Override
    protected Value read(ClientboundRemoveEntitiesPacket packet) {
        return new Value(packet.getEntityIds().intStream().boxed().toList());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
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
}
