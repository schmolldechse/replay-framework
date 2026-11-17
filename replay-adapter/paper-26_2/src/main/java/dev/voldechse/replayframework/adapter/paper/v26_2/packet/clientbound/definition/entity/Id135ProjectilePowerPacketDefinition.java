package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.entity;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:projectile_power}.
 *
 * <p>The acceleration value is independent from the adjacent projectile motion update. Keep its
 * recorded double intact while rewriting the projectile entity ID for the playback session.</p>
 */
final class Id135ProjectilePowerPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundProjectilePowerPacket,
        Id135ProjectilePowerPacketDefinition.Value> {

    static final int PACKET_ID = 0x87;
    static final String IDENTIFIER = "minecraft:projectile_power";

    Id135ProjectilePowerPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundProjectilePowerPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily.ENTITY_METADATA);
    }

    @Override
    protected Value read(ClientboundProjectilePowerPacket packet) {
        return new Value(packet.getId(), packet.getAccelerationPower());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.accelerationPower()));
    }

    @Override
    protected ClientboundProjectilePowerPacket rebuild(Value value) {
        return new ClientboundProjectilePowerPacket(value.entityId(), value.accelerationPower());
    }

    record Value(int entityId, double accelerationPower) {}

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution.BROADCAST;
    }
}
