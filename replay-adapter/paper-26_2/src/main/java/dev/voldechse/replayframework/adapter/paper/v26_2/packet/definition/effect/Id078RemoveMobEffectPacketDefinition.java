package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Objects;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.world.effect.MobEffect;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:remove_mob_effect}.
 *
 * <p>The entity ID targets the replay entity whose status effect is removed.
 * The effect itself is a registry-bound holder, so retain it for the native
 * registry-aware codec instead of replacing it with a version-specific ID.</p>
 */
final class Id078RemoveMobEffectPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundRemoveMobEffectPacket,
        Id078RemoveMobEffectPacketDefinition.Value> {

    static final int PACKET_ID = 0x4E;
    static final String IDENTIFIER = "minecraft:remove_mob_effect";

    Id078RemoveMobEffectPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundRemoveMobEffectPacket.class);
    }

    @Override
    protected Value read(ClientboundRemoveMobEffectPacket packet) {
        return new Value(packet.entityId(), packet.effect());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(
                new Value(context.mapEntityId(value.entityId()), value.effect()));
    }

    @Override
    protected ClientboundRemoveMobEffectPacket rebuild(Value value) {
        return new ClientboundRemoveMobEffectPacket(value.entityId(), value.effect());
    }

    record Value(int entityId, Holder<MobEffect> effect) {

        Value {
            Objects.requireNonNull(effect, "effect");
        }
    }
}
