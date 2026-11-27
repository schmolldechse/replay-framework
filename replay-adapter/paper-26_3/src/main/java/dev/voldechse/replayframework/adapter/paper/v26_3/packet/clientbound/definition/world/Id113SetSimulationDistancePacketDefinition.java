package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_simulation_distance}.
 *
 * <p>The server-wide simulation distance is shared replay state. Preserve the native VarInt
 * unchanged so the client uses the value that was active in the recording.</p>
 */
final class Id113SetSimulationDistancePacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSetSimulationDistancePacket,
        Id113SetSimulationDistancePacketDefinition.Value> {

    static final int PACKET_ID = 0x71;
    static final String IDENTIFIER = "minecraft:set_simulation_distance";

    Id113SetSimulationDistancePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetSimulationDistancePacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.GLOBAL_STATE);
    }

    @Override
    protected Value read(ClientboundSetSimulationDistancePacket packet) {
        return new Value(packet.simulationDistance());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetSimulationDistancePacket rebuild(Value value) {
        return new ClientboundSetSimulationDistancePacket(value.simulationDistance());
    }

    record Value(int simulationDistance) {}

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.VIEW_LOCAL;
    }
}
