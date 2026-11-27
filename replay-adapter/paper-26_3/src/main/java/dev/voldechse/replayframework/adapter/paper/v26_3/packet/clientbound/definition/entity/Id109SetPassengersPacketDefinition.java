package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;

/** Complete read/rewrite/rebuild contract for {@code minecraft:set_passengers}. */
final class Id109SetPassengersPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSetPassengersPacket,
        Id109SetPassengersPacketDefinition.Value> {

    static final int PACKET_ID = 0x6D;
    static final String IDENTIFIER = "minecraft:set_passengers";

    Id109SetPassengersPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSetPassengersPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.PASSENGERS);
    }

    @Override
    protected Value read(ClientboundSetPassengersPacket packet) {
        return new Value(packet.getVehicle(), packet.getPassengers());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        int[] passengerIds = Arrays.stream(value.passengerIds())
                .map(context::mapEntityId)
                .toArray();
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.vehicleId()), passengerIds));
    }

    @Override
    protected ClientboundSetPassengersPacket rebuild(Value value) {
        // The generated packet has only an Entity-based public constructor. Decode the same
        // native VarInt + VarInt-array body so replay IDs can be rebuilt without live entities.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(value.vehicleId());
            buffer.writeVarIntArray(value.passengerIds());
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    record Value(int vehicleId, int[] passengerIds) {

        Value {
            Objects.requireNonNull(passengerIds, "passengerIds");
            passengerIds = passengerIds.clone();
        }

        @Override
        public int[] passengerIds() {
            return passengerIds.clone();
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
