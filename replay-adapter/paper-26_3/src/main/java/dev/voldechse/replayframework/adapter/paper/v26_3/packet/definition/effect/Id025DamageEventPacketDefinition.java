package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.phys.Vec3;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:damage_event}.
 *
 * <p>This ordered scene effect identifies the damaged entity and can identify
 * both the causing entity and the direct source independently. The protocol
 * uses {@code -1} as the absent-entity sentinel for either source reference.
 * A source position is optional and, when present, takes precedence over both
 * entity references when the client reconstructs the damage source. The
 * registry-bound damage type holder is retained so the connection codec can
 * resolve its protocol ID against that connection's registry.</p>
 */
final class Id025DamageEventPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundDamageEventPacket,
        Id025DamageEventPacketDefinition.Value> {

    static final int PACKET_ID = 0x19;
    static final String IDENTIFIER = "minecraft:damage_event";

    Id025DamageEventPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundDamageEventPacket.class);
    }

    @Override
    protected Value read(ClientboundDamageEventPacket packet) {
        return new Value(
                packet.entityId(),
                packet.sourceType(),
                packet.sourceCauseId(),
                packet.sourceDirectId(),
                packet.sourcePosition());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()),
                value.sourceType(),
                mapOptionalEntityId(value.sourceCauseId(), context),
                mapOptionalEntityId(value.sourceDirectId(), context),
                value.sourcePosition()));
    }

    @Override
    protected ClientboundDamageEventPacket rebuild(Value value) {
        return new ClientboundDamageEventPacket(
                value.entityId(),
                value.sourceType(),
                value.sourceCauseId(),
                value.sourceDirectId(),
                value.sourcePosition());
    }

    private static int mapOptionalEntityId(int entityId, Paper26_3ReplayPacketRewriter context) {
        return entityId == -1 ? -1 : context.mapEntityId(entityId);
    }

    record Value(
            int entityId,
            Holder<DamageType> sourceType,
            int sourceCauseId,
            int sourceDirectId,
            Optional<Vec3> sourcePosition) {

        Value {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(sourcePosition, "sourcePosition");
            sourcePosition.ifPresent(position -> {
                requireFinite(position.x(), "sourcePosition.x");
                requireFinite(position.y(), "sourcePosition.y");
                requireFinite(position.z(), "sourcePosition.z");
            });
        }

        private static void requireFinite(double value, String field) {
            if (!Double.isFinite(value)) {
                throw new IncompatibleAdapterException(
                        "damage_event contains a non-finite " + field + " value");
            }
        }
    }
}
