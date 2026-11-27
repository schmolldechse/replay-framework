package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
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
final class Id115SetTimePacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSetTimePacket,
        Id115SetTimePacketDefinition.Value> {

    static final int PACKET_ID = 0x73;
    static final String IDENTIFIER = "minecraft:set_time";

    Id115SetTimePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundSetTimePacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.GLOBAL_STATE);
    }

    @Override
    protected Value read(ClientboundSetTimePacket packet) {
        return new Value(packet.gameTime(), packet.clockUpdates());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
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

    /** Time is sampled by the scene. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.deliveredByScene();
    }
}
