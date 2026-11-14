package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.List;
import java.util.Objects;
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
