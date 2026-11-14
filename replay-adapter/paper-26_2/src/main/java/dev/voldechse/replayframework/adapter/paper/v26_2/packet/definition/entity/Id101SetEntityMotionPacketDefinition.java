package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;

/** Complete read/rewrite/rebuild contract for {@code minecraft:set_entity_motion}. */
final class Id101SetEntityMotionPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetEntityMotionPacket,
        Id101SetEntityMotionPacketDefinition.Value> {

    static final int PACKET_ID = 0x65;
    static final String IDENTIFIER = "minecraft:set_entity_motion";

    Id101SetEntityMotionPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSetEntityMotionPacket.class);
    }

    @Override
    protected Value read(ClientboundSetEntityMotionPacket packet) {
        return new Value(packet.id(), packet.movement());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.movement()));
    }

    @Override
    protected ClientboundSetEntityMotionPacket rebuild(Value value) {
        return new ClientboundSetEntityMotionPacket(value.entityId(), value.movement());
    }

    record Value(int entityId, Vec3 movement) {

        Value {
            Objects.requireNonNull(movement, "movement");
        }
    }
}
