package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import com.mojang.datafixers.util.Pair;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketCaptureState;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2CaptureContexts;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:set_equipment}.
 *
 * <p>The native codec owns the continuation-bit and optional item-stack framing.
 * This definition preserves the ordered entries while changing only the entity
 * identity and detaching mutable item stacks from both native packets.
 */
final class Id102SetEquipmentPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundSetEquipmentPacket,
        Id102SetEquipmentPacketDefinition.Value> {

    static final int PACKET_ID = 0x66;
    static final String IDENTIFIER = "minecraft:set_equipment";

    Id102SetEquipmentPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.stateful(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundSetEquipmentPacket.class);
    }

    @Override
    public java.util.Optional<
            dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily> checkpointFamily() {
        return java.util.Optional.of(
                dev.voldechse.replayframework.adapter.paper.packet.PacketCheckpointFamily.EQUIPMENT);
    }


    @Override
    public boolean deduplicateRecipientCopies() {
        return true;
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
        return Paper26_2CaptureContexts.atEntity(
                base, state, connectionId,
                ClientboundSetEquipmentPacket.class.cast(decodedPacket).getEntity());
    }

    @Override
    public boolean suppressesRecipientState(
            Object decodedPacket, PacketCaptureState state, long serverTick) {
        return state.equipmentIsAuthoritative(
                ClientboundSetEquipmentPacket.class.cast(decodedPacket).getEntity());
    }

    @Override
    protected Value read(ClientboundSetEquipmentPacket packet) {
        return new Value(
                packet.getEntity(),
                packet.getSlots().stream()
                        .map(entry -> new EquipmentEntry(entry.getFirst(), entry.getSecond()))
                        .toList());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.entries()));
    }

    @Override
    protected ClientboundSetEquipmentPacket rebuild(Value value) {
        return new ClientboundSetEquipmentPacket(
                value.entityId(),
                value.entries().stream()
                        .map(EquipmentEntry::toPacketEntry)
                        .toList());
    }

    /** Keeps the wire order; repeated slots must not be collapsed into a map. */
    record Value(int entityId, List<EquipmentEntry> entries) {

        Value {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /** Holds a detached stack and returns copies so mutable NMS state never escapes. */
    static final class EquipmentEntry {

        private final EquipmentSlot slot;
        private final ItemStack itemStack;

        EquipmentEntry(EquipmentSlot slot, ItemStack itemStack) {
            this.slot = Objects.requireNonNull(slot, "slot");
            this.itemStack = Objects.requireNonNull(itemStack, "itemStack").copy();
        }

        EquipmentSlot slot() {
            return slot;
        }

        ItemStack itemStack() {
            return itemStack.copy();
        }

        Pair<EquipmentSlot, ItemStack> toPacketEntry() {
            return Pair.of(slot, itemStack.copy());
        }
    }

    @Override
    public dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution distribution() {
        return dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution.BROADCAST;
    }
}
