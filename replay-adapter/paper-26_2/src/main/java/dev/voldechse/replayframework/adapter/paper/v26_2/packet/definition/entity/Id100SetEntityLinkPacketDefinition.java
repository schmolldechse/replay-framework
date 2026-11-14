package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:set_entity_link}. */
final class Id100SetEntityLinkPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetEntityLinkPacket,
        Id100SetEntityLinkPacketDefinition.Value> {

    static final int PACKET_ID = 0x64;
    static final String IDENTIFIER = "minecraft:set_entity_link";

    Id100SetEntityLinkPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSetEntityLinkPacket.class);
    }

    @Override
    protected Value read(ClientboundSetEntityLinkPacket packet) {
        return new Value(packet.getSourceId(), packet.getDestId());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        int destinationId = value.destinationId() == 0
                ? 0
                : context.mapEntityId(value.destinationId());
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.sourceId()), destinationId));
    }

    @Override
    protected ClientboundSetEntityLinkPacket rebuild(Value value) {
        // Paper 26.2 exposes only an Entity-based constructor. Decode the exact native
        // two-Int shape so a mapped pair can be rebuilt without looking up live entities.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(value.sourceId());
            buffer.writeInt(value.destinationId());
            return ClientboundSetEntityLinkPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(int sourceId, int destinationId) {
        // Destination ID zero is Paper 26.2's unlink sentinel; other values are entity IDs.
    }
}
