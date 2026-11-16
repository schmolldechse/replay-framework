package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/** Complete read/rewrite/rebuild contract for {@code minecraft:update_attributes}. */
final class Id131UpdateAttributesPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundUpdateAttributesPacket,
        Id131UpdateAttributesPacketDefinition.Value> {

    static final int PACKET_ID = 0x83;
    static final String IDENTIFIER = "minecraft:update_attributes";

    Id131UpdateAttributesPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundUpdateAttributesPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily.ENTITY_METADATA);
    }

    @Override
    protected Value read(ClientboundUpdateAttributesPacket packet) {
        return new Value(
                packet.getEntityId(),
                packet.getValues().stream()
                        .map(snapshot -> new AttributeValue(
                                snapshot.attribute(), snapshot.base(), List.copyOf(snapshot.modifiers())))
                        .toList());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.attributes()));
    }

    @Override
    protected ClientboundUpdateAttributesPacket rebuild(Value value) {
        // Paper exposes a collection-of-AttributeInstance constructor; the snapshot-list
        // constructor is private. Persistence is not part of this packet's wire shape, so
        // transient modifiers preserve the same synced modifier values without extra state.
        List<AttributeInstance> attributes = value.attributes().stream()
                .map(Id131UpdateAttributesPacketDefinition::toAttributeInstance)
                .toList();
        return new ClientboundUpdateAttributesPacket(value.entityId(), attributes);
    }

    private static AttributeInstance toAttributeInstance(AttributeValue value) {
        Set<AttributeModifier> modifiers = Collections.unmodifiableSet(
                new LinkedHashSet<>(value.modifiers()));
        return new AttributeInstance(value.attribute(), ignored -> {}) {
            @Override
            public double getBaseValue() {
                // setBaseValue considers -0.0 equal to +0.0; the packet wire value does not.
                return value.baseValue();
            }

            @Override
            public Set<AttributeModifier> getModifiers() {
                return modifiers;
            }
        };
    }

    record Value(int entityId, List<AttributeValue> attributes) {

        Value {
            attributes = List.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }
    }

    record AttributeValue(
            Holder<Attribute> attribute,
            double baseValue,
            List<AttributeModifier> modifiers) {

        AttributeValue {
            Objects.requireNonNull(attribute, "attribute");
            modifiers = List.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
            Set<Identifier> modifierIds = new HashSet<>();
            for (AttributeModifier modifier : modifiers) {
                Objects.requireNonNull(modifier, "modifier");
                if (!modifierIds.add(modifier.id())) {
                    throw new IncompatibleAdapterException(
                            "update_attributes contains duplicate modifier ID " + modifier.id());
                }
            }
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution.BROADCAST;
    }
}
