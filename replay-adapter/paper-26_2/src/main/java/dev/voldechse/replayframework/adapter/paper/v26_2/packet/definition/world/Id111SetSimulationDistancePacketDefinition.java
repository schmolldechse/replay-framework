package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_simulation_distance}.
 *
 * <p>The server-wide simulation distance is shared replay state. Preserve the native VarInt
 * unchanged so the client uses the value that was active in the recording.</p>
 */
final class Id111SetSimulationDistancePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetSimulationDistancePacket,
        Id111SetSimulationDistancePacketDefinition.Value> {

    static final int PACKET_ID = 0x6F;
    static final String IDENTIFIER = "minecraft:set_simulation_distance";

    Id111SetSimulationDistancePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetSimulationDistancePacket.class);
    }

    @Override
    protected Value read(ClientboundSetSimulationDistancePacket packet) {
        return new Value(packet.simulationDistance());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetSimulationDistancePacket rebuild(Value value) {
        return new ClientboundSetSimulationDistancePacket(value.simulationDistance());
    }

    record Value(int simulationDistance) {}
}
