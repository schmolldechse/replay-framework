package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.effect;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:take_item_entity}. */
final class Id127TakeItemEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundTakeItemEntityPacket,
        Id127TakeItemEntityPacketDefinition.Value> {

    static final int PACKET_ID = 0x7F;
    static final String IDENTIFIER = "minecraft:take_item_entity";

    Id127TakeItemEntityPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundTakeItemEntityPacket.class);
    }

    @Override
    protected Value read(ClientboundTakeItemEntityPacket packet) {
        return new Value(packet.getItemId(), packet.getPlayerId(), packet.getAmount());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.itemEntityId()),
                context.mapEntityId(value.collectorEntityId()),
                value.amount()));
    }

    @Override
    protected ClientboundTakeItemEntityPacket rebuild(Value value) {
        return new ClientboundTakeItemEntityPacket(
                value.itemEntityId(), value.collectorEntityId(), value.amount());
    }

    /** Both leading fields are entity references; amount is the original pickup stack count. */
    record Value(int itemEntityId, int collectorEntityId, int amount) {}

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
