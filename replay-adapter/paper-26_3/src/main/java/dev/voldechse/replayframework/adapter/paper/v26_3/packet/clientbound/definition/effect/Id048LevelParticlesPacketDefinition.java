package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import java.util.Objects;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.level.gameevent.EntityPositionSource;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:level_particles}.
 *
 * <p>Particle payloads are registry-coded and remain native to Paper 26.3.
 * Vibration particles are the exception that needs identity-aware handling:
 * their destination may be an entity position source containing a replay
 * entity ID. Block destinations and all other particle options are preserved.
 * Paper 26.3 carries a maximum speed per axis and a randomization type; both
 * are preserved as recorded.</p>
 */
final class Id048LevelParticlesPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundLevelParticlesPacket,
        Id048LevelParticlesPacketDefinition.Value> {

    static final int PACKET_ID = 0x30;
    static final String IDENTIFIER = "minecraft:level_particles";

    Id048LevelParticlesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundLevelParticlesPacket.class);
    }

    @Override
    protected Value read(ClientboundLevelParticlesPacket packet) {
        return new Value(
                packet.overrideLimiter(),
                packet.alwaysShow(),
                packet.x(),
                packet.y(),
                packet.z(),
                packet.xDist(),
                packet.yDist(),
                packet.zDist(),
                packet.xMaxSpeed(),
                packet.yMaxSpeed(),
                packet.zMaxSpeed(),
                packet.count(),
                packet.randomizationType(),
                packet.particle());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                value.overrideLimiter(),
                value.alwaysShow(),
                value.x(),
                value.y(),
                value.z(),
                value.xDist(),
                value.yDist(),
                value.zDist(),
                value.xMaxSpeed(),
                value.yMaxSpeed(),
                value.zMaxSpeed(),
                value.count(),
                value.randomizationType(),
                rewriteParticle(value.particle(), context)));
    }

    @Override
    protected ClientboundLevelParticlesPacket rebuild(Value value) {
        return new ClientboundLevelParticlesPacket(
                value.particle(),
                value.overrideLimiter(),
                value.alwaysShow(),
                value.x(),
                value.y(),
                value.z(),
                value.xDist(),
                value.yDist(),
                value.zDist(),
                value.xMaxSpeed(),
                value.yMaxSpeed(),
                value.zMaxSpeed(),
                value.count(),
                value.randomizationType());
    }

    private static ParticleOptions rewriteParticle(
            ParticleOptions particle,
            Paper26_3ReplayPacketRewriter context) {
        if (particle instanceof VibrationParticleOption vibration
                && vibration.getDestination() instanceof EntityPositionSource entitySource) {
            return new VibrationParticleOption(
                    rewriteEntityPositionSource(entitySource, context),
                    vibration.getArrivalInTicks());
        }
        return particle;
    }

    private static EntityPositionSource rewriteEntityPositionSource(
            EntityPositionSource source,
            Paper26_3ReplayPacketRewriter context) {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            EntityPositionSource.STREAM_CODEC.encode(encoded, source);
            int entityId = encoded.readVarInt();
            float yOffset = encoded.readFloat();
            if (!Float.isFinite(yOffset)) {
                throw new IncompatibleAdapterException(
                        "level_particles vibration destination contains a non-finite yOffset");
            }

            FriendlyByteBuf rewritten = new FriendlyByteBuf(Unpooled.buffer());
            try {
                rewritten.writeVarInt(context.mapEntityId(entityId));
                rewritten.writeFloat(yOffset);
                return EntityPositionSource.STREAM_CODEC.decode(rewritten);
            } finally {
                rewritten.release();
            }
        } finally {
            encoded.release();
        }
    }

    record Value(
            boolean overrideLimiter,
            boolean alwaysShow,
            double x,
            double y,
            double z,
            float xDist,
            float yDist,
            float zDist,
            float xMaxSpeed,
            float yMaxSpeed,
            float zMaxSpeed,
            int count,
            ClientboundLevelParticlesPacket.RandomizationType randomizationType,
            ParticleOptions particle) {

        Value {
            Objects.requireNonNull(particle, "particle");
            Objects.requireNonNull(randomizationType, "randomizationType");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(xDist, "xDist");
            requireFinite(yDist, "yDist");
            requireFinite(zDist, "zDist");
            requireFinite(xMaxSpeed, "xMaxSpeed");
            requireFinite(yMaxSpeed, "yMaxSpeed");
            requireFinite(zMaxSpeed, "zMaxSpeed");
        }

        private static void requireFinite(double value, String field) {
            if (!Double.isFinite(value)) {
                throw new IncompatibleAdapterException(
                        "level_particles contains a non-finite " + field);
            }
        }

        private static void requireFinite(float value, String field) {
            if (!Float.isFinite(value)) {
                throw new IncompatibleAdapterException(
                        "level_particles contains a non-finite " + field);
            }
        }
    }

    /** Places the effect in the world so that a region recording can accept it. */
    @Override
    public dev.voldechse.replayframework.adapter.CaptureContext captureContext(
            Object decodedPacket,
            dev.voldechse.replayframework.adapter.PacketDescriptor descriptor,
            java.util.UUID connectionId,
            dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCaptureState state,
            long serverTick) {
        var base = super.captureContext(
                decodedPacket, descriptor, connectionId, state, serverTick);
        var packet = net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket.class.cast(decodedPacket);
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts.atBlockPosition(
                base,
                new dev.voldechse.replayframework.api.recording.BlockPosition(
                        (int) Math.floor(packet.x()), (int) Math.floor(packet.y()), (int) Math.floor(packet.z())));
    }

    /** One packet object, or equal bytes, for every recipient in range. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.identicalPayload();
    }
}
