package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_time}.
 *
 * <p>Clock updates are deltas keyed by registry-backed world clocks: a packet can contain no
 * updates, one changed clock, or several clocks. Preserve that map as sent rather than inferring
 * state for clocks absent from a delta.</p>
 */
final class Id113SetTimePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetTimePacket,
        Id113SetTimePacketDefinition.Value> {

    static final int PACKET_ID = 0x71;
    static final String IDENTIFIER = "minecraft:set_time";

    Id113SetTimePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetTimePacket.class);
    }

    @Override
    protected Value read(ClientboundSetTimePacket packet) {
        return new Value(packet.gameTime(), packet.clockUpdates());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundSetTimePacket rebuild(Value value) {
        return new ClientboundSetTimePacket(value.gameTime(), value.clockUpdates());
    }

    record Value(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> clockUpdates) {
        Value {
            clockUpdates = Map.copyOf(Objects.requireNonNull(clockUpdates, "clockUpdates"));
        }
    }
}
