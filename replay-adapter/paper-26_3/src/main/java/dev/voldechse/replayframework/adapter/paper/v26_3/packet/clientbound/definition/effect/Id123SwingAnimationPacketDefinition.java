package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.effect;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:swing_animation}.
 *
 * <p>Paper 26.3 sends an entity's arm swing as its own packet, through the
 * chunk source to every tracker of the entity, Argus eyes included; the
 * swing actions left {@code animate}. The hand and the swing animation of
 * the held item are kept as recorded; only the entity ID is mapped into the
 * playback session.</p>
 */
final class Id123SwingAnimationPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundSwingAnimationPacket,
        Id123SwingAnimationPacketDefinition.Value> {

    static final int PACKET_ID = 0x7B;
    static final String IDENTIFIER = "minecraft:swing_animation";

    Id123SwingAnimationPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSwingAnimationPacket.class);
    }

    @Override
    public CaptureContext captureContext(
            Object decodedPacket,
            PacketDescriptor descriptor,
            UUID connectionId,
            PacketCaptureState state,
            long serverTick) {
        CaptureContext base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        return CaptureContexts.atEntity(
                base, state, connectionId,
                ClientboundSwingAnimationPacket.class.cast(decodedPacket).entityId());
    }

    @Override
    protected Value read(ClientboundSwingAnimationPacket packet) {
        return new Value(packet.entityId(), packet.hand(), packet.animation());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.hand(), value.animation()));
    }

    @Override
    protected ClientboundSwingAnimationPacket rebuild(Value value) {
        return new ClientboundSwingAnimationPacket(
                value.entityId(), value.hand(), value.animation());
    }

    record Value(int entityId, InteractionHand hand, SwingAnimation animation) {

        Value {
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(animation, "animation");
        }
    }

    @Override
    public PacketDistribution distribution() {
        return PacketDistribution.BROADCAST;
    }
}
