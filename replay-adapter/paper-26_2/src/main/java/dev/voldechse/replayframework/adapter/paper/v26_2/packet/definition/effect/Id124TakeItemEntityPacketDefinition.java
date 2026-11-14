package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:take_item_entity}. */
final class Id124TakeItemEntityPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundTakeItemEntityPacket,
        Id124TakeItemEntityPacketDefinition.Value> {

    static final int PACKET_ID = 0x7C;
    static final String IDENTIFIER = "minecraft:take_item_entity";

    Id124TakeItemEntityPacketDefinition() {
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
            Paper26_2ReplayPacketRewriter context) {
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
}
