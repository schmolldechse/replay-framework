package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:hurt_animation}.
 *
 * <p>The packet plays a directional damage animation on its target entity.
 * Remap that entity into the replay identity space and preserve the yaw as
 * sent; it is a relative direction, so normalizing it would alter the recorded
 * value. Reject non-finite yaws before they reach the client's animation path.</p>
 */
final class Id042HurtAnimationPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundHurtAnimationPacket,
        Id042HurtAnimationPacketDefinition.Value> {

    static final int PACKET_ID = 0x2A;
    static final String IDENTIFIER = "minecraft:hurt_animation";

    Id042HurtAnimationPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundHurtAnimationPacket.class);
    }

    @Override
    protected Value read(ClientboundHurtAnimationPacket packet) {
        return new Value(packet.id(), packet.yaw());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(
                new Value(context.mapEntityId(value.entityId()), value.yaw()));
    }

    @Override
    protected ClientboundHurtAnimationPacket rebuild(Value value) {
        return new ClientboundHurtAnimationPacket(value.entityId(), value.yaw());
    }

    record Value(int entityId, float yaw) {

        Value {
            if (!Float.isFinite(yaw)) {
                throw new IncompatibleAdapterException("hurt_animation yaw must be finite");
            }
        }
    }
}
