package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
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
 * <p>Particle payloads are registry-coded and remain native to Paper 26.2.
 * Vibration particles are the exception that needs identity-aware handling:
 * their destination may be an entity position source containing a replay
 * entity ID. Block destinations and all other particle options are preserved.</p>
 */
final class Id047LevelParticlesPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundLevelParticlesPacket,
        Id047LevelParticlesPacketDefinition.Value> {

    static final int PACKET_ID = 0x2F;
    static final String IDENTIFIER = "minecraft:level_particles";

    Id047LevelParticlesPacketDefinition() {
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
                packet.isOverrideLimiter(),
                packet.alwaysShow(),
                packet.getX(),
                packet.getY(),
                packet.getZ(),
                packet.getXDist(),
                packet.getYDist(),
                packet.getZDist(),
                packet.getMaxSpeed(),
                packet.getCount(),
                packet.getParticle());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                value.overrideLimiter(),
                value.alwaysShow(),
                value.x(),
                value.y(),
                value.z(),
                value.xDist(),
                value.yDist(),
                value.zDist(),
                value.maxSpeed(),
                value.count(),
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
                value.maxSpeed(),
                value.count());
    }

    private static ParticleOptions rewriteParticle(
            ParticleOptions particle,
            Paper26_2ReplayPacketRewriter context) {
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
            Paper26_2ReplayPacketRewriter context) {
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
            float maxSpeed,
            int count,
            ParticleOptions particle) {

        Value {
            Objects.requireNonNull(particle, "particle");
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
            requireFinite(xDist, "xDist");
            requireFinite(yDist, "yDist");
            requireFinite(zDist, "zDist");
            requireFinite(maxSpeed, "maxSpeed");
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
}
