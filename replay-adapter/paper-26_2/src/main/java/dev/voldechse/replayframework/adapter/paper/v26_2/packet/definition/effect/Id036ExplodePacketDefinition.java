package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.phys.Vec3;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:explode}.
 *
 * <p>Explosion particles and sounds are registry-aware payloads, and the block
 * particle list also carries per-entry weights, scaling and speed. Preserve
 * them as native version-bound values. Remove the optional player knockback
 * so a recorded player's impulse never moves the observer. Vanilla vibration
 * particle destinations are the only built-in nested effect here that can
 * carry an entity ID; map those destinations into the playback identity space.
 * Custom registry particle payloads remain opaque.</p>
 */
final class Id036ExplodePacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundExplodePacket,
        Id036ExplodePacketDefinition.Value> {

    static final int PACKET_ID = 0x24;
    static final String IDENTIFIER = "minecraft:explode";

    Id036ExplodePacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundExplodePacket.class);
    }

    @Override
    protected Value read(ClientboundExplodePacket packet) {
        return new Value(
                packet.center(),
                packet.radius(),
                packet.blockCount(),
                packet.playerKnockback(),
                packet.explosionParticle(),
                packet.explosionSound(),
                packet.blockParticles());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        ArrayList<Weighted<ExplosionParticleInfo>> blockParticles = new ArrayList<>();
        for (Weighted<ExplosionParticleInfo> entry : value.blockParticles().unwrap()) {
            ExplosionParticleInfo info = entry.value();
            blockParticles.add(new Weighted<>(
                    new ExplosionParticleInfo(
                            rewriteParticle(info.particle(), context),
                            info.scaling(),
                            info.speed()),
                    entry.weight()));
        }

        return ValueRewriteResult.send(new Value(
                value.center(),
                value.radius(),
                value.blockCount(),
                Optional.empty(),
                rewriteParticle(value.explosionParticle(), context),
                value.explosionSound(),
                WeightedList.of(blockParticles)));
    }

    @Override
    protected ClientboundExplodePacket rebuild(Value value) {
        return new ClientboundExplodePacket(
                value.center(),
                value.radius(),
                value.blockCount(),
                value.playerKnockback(),
                value.explosionParticle(),
                value.explosionSound(),
                value.blockParticles());
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
                        "explode vibration destination contains a non-finite yOffset value");
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
            Vec3 center,
            float radius,
            int blockCount,
            Optional<Vec3> playerKnockback,
            ParticleOptions explosionParticle,
            Holder<SoundEvent> explosionSound,
            WeightedList<ExplosionParticleInfo> blockParticles) {

        Value {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(playerKnockback, "playerKnockback");
            Objects.requireNonNull(explosionParticle, "explosionParticle");
            Objects.requireNonNull(explosionSound, "explosionSound");
            Objects.requireNonNull(blockParticles, "blockParticles");
            requireFinite(center.x, "center.x");
            requireFinite(center.y, "center.y");
            requireFinite(center.z, "center.z");
            requireFinite(radius, "radius");
            playerKnockback.ifPresent(knockback -> {
                Objects.requireNonNull(knockback, "playerKnockback value");
                requireFinite(knockback.x, "playerKnockback.x");
                requireFinite(knockback.y, "playerKnockback.y");
                requireFinite(knockback.z, "playerKnockback.z");
            });
            for (Weighted<ExplosionParticleInfo> entry : blockParticles.unwrap()) {
                Objects.requireNonNull(entry, "blockParticles entry");
                ExplosionParticleInfo info = Objects.requireNonNull(
                        entry.value(), "blockParticles value");
                Objects.requireNonNull(info.particle(), "blockParticles particle");
                requireFinite(info.scaling(), "blockParticles scaling");
                requireFinite(info.speed(), "blockParticles speed");
            }
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IncompatibleAdapterException(
                    "explode contains a non-finite " + field + " value");
        }
    }
}
