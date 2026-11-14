package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gameevent.EntityPositionSource;

/** Complete read/rewrite/rebuild contract for {@code minecraft:set_entity_data}. */
final class Id099SetEntityDataPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetEntityDataPacket,
        Id099SetEntityDataPacketDefinition.Value> {

    static final int PACKET_ID = 0x63;
    static final String IDENTIFIER = "minecraft:set_entity_data";

    Id099SetEntityDataPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSetEntityDataPacket.class);
    }

    @Override
    protected Value read(ClientboundSetEntityDataPacket packet) {
        return new Value(packet.id(), packet.packedItems());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        EntityType<?> entityType = context.recordedEntityType(value.entityId()).orElse(null);
        List<SynchedEntityData.DataValue<?>> packedItems =
                new ArrayList<>(value.packedItems().size());
        for (SynchedEntityData.DataValue<?> item : value.packedItems()) {
            packedItems.add(rewriteDataValue(item, entityType, context));
        }
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), packedItems));
    }

    @Override
    protected ClientboundSetEntityDataPacket rebuild(Value value) {
        return new ClientboundSetEntityDataPacket(value.entityId(), value.packedItems());
    }

    private static SynchedEntityData.DataValue<?> rewriteDataValue(
            SynchedEntityData.DataValue<?> item,
            EntityType<?> entityType,
            Paper26_2ReplayPacketRewriter context) {
        if (item.serializer() == EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE) {
            @SuppressWarnings("unchecked")
            Optional<EntityReference<LivingEntity>> references =
                    (Optional<EntityReference<LivingEntity>>) item.value();
            if (references.isEmpty()) {
                return item;
            }
            return withValue(item, references.map(reference ->
                    EntityReference.<LivingEntity>of(context.mapUuid(reference.getUUID()))));
        }

        if (item.serializer() == EntityDataSerializers.OPTIONAL_UNSIGNED_INT) {
            OptionalInt reference = (OptionalInt) item.value();
            if (reference.isEmpty()) {
                return item;
            }
            // Paper 26.2 also uses this serializer for Player shoulder-parrot variants, so the
            // serializer alone cannot tell a variant ID from a live entity ID.
            if (entityType == null) {
                throw new IncompatibleAdapterException(
                        "cannot isolate entity-data OptionalInt without the target entity type");
            }
            if (entityType == EntityTypes.FIREWORK_ROCKET || entityType == EntityTypes.FROG) {
                return withValue(item, OptionalInt.of(context.mapEntityId(reference.getAsInt())));
            }
            if (entityType == EntityTypes.PLAYER) {
                // Player metadata uses this same serializer for parrot variant IDs.
                return item;
            }
            throw new IncompatibleAdapterException(
                    "cannot safely interpret entity-data OptionalInt for " + entityType);
        }

        if (item.serializer() == EntityDataSerializers.PARTICLE) {
            return withValue(item, rewriteParticle((ParticleOptions) item.value(), context));
        }

        if (item.serializer() == EntityDataSerializers.PARTICLES) {
            @SuppressWarnings("unchecked")
            List<ParticleOptions> particles = (List<ParticleOptions>) item.value();
            return withValue(item, particles.stream()
                    .map(particle -> rewriteParticle(particle, context))
                    .toList());
        }

        return item;
    }

    private static ParticleOptions rewriteParticle(
            ParticleOptions particle,
            Paper26_2ReplayPacketRewriter context) {
        if (!(particle instanceof VibrationParticleOption vibration)
                || !(vibration.getDestination() instanceof EntityPositionSource entitySource)) {
            return particle;
        }
        return new VibrationParticleOption(
                rewriteEntityPositionSource(entitySource, context),
                vibration.getArrivalInTicks());
    }

    private static EntityPositionSource rewriteEntityPositionSource(
            EntityPositionSource source,
            Paper26_2ReplayPacketRewriter context) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            EntityPositionSource.STREAM_CODEC.encode(encoded, source);
            int recordedEntityId = VarInt.read(encoded);
            float yOffset = encoded.readFloat();

            ByteBuf rewritten = Unpooled.buffer();
            try {
                VarInt.write(rewritten, context.mapEntityId(recordedEntityId));
                rewritten.writeFloat(yOffset);
                return EntityPositionSource.STREAM_CODEC.decode(rewritten);
            } finally {
                rewritten.release();
            }
        } finally {
            encoded.release();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SynchedEntityData.DataValue<?> withValue(
            SynchedEntityData.DataValue<?> original,
            Object rewrittenValue) {
        return new SynchedEntityData.DataValue(
                original.id(), original.serializer(), rewrittenValue);
    }

    record Value(int entityId, List<SynchedEntityData.DataValue<?>> packedItems) {

        Value {
            Objects.requireNonNull(packedItems, "packedItems");
            packedItems = List.copyOf(packedItems);
            for (SynchedEntityData.DataValue<?> item : packedItems) {
                if (item.id() < 0 || item.id() >= ClientboundSetEntityDataPacket.EOF_MARKER) {
                    throw new IncompatibleAdapterException(
                            "set_entity_data metadata IDs must be in the unsigned-byte range 0-254");
                }
            }
        }
    }
}
