package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:set_entity_link}. */
final class Id102SetEntityLinkPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSetEntityLinkPacket,
        Id102SetEntityLinkPacketDefinition.Value> {

    static final int PACKET_ID = 0x66;
    static final String IDENTIFIER = "minecraft:set_entity_link";

    Id102SetEntityLinkPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSetEntityLinkPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.PASSENGERS);
    }

    @Override
    protected Value read(ClientboundSetEntityLinkPacket packet) {
        return new Value(packet.getSourceId(), packet.getDestId());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        int destinationId = value.destinationId() == 0
                ? 0
                : context.mapEntityId(value.destinationId());
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.sourceId()), destinationId));
    }

    @Override
    protected ClientboundSetEntityLinkPacket rebuild(Value value) {
        // Paper 26.3 exposes only an Entity-based constructor. Decode the exact native
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
        // Destination ID zero is Paper 26.3's unlink sentinel; other values are entity IDs.
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
