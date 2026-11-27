package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:level_event}.
 *
 * <p>Event IDs select one-shot sounds, particles, animations, and related
 * world effects. The {@code data} integer is intentionally retained as an
 * opaque signed value: its meaning depends on the event ID and can be a
 * registry ID, block-state ID, direction, color, count, or other parameter.
 * No common interpretation is safe here. The global flag is also preserved
 * because it controls whether sound volume is distance-relative.</p>
 */
final class Id047LevelEventPacketDefinition extends RebuildingPacketDefinition<
        Paper26_3ReplayPacketRewriter,
        ClientboundLevelEventPacket,
        Id047LevelEventPacketDefinition.Value> {

    static final int PACKET_ID = 0x2F;
    static final String IDENTIFIER = "minecraft:level_event";

    Id047LevelEventPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(PacketDescriptor.PlaybackScope.SAFE),
                ClientboundLevelEventPacket.class);
    }

    @Override
    protected Value read(ClientboundLevelEventPacket packet) {
        return new Value(
                packet.getType(),
                packet.getPos(),
                packet.getData(),
                packet.isGlobalEvent());
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_3ReplayPacketRewriter context) {
        return ValueRewriteResult.send(value);
    }

    @Override
    protected ClientboundLevelEventPacket rebuild(Value value) {
        return new ClientboundLevelEventPacket(
                value.eventId(), value.position(), value.data(), value.global());
    }

    record Value(int eventId, BlockPos position, int data, boolean global) {

        Value {
            if (position == null) {
                throw new IncompatibleAdapterException(
                        "level_event position must not be null");
            }
            position = position.immutable();
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
        var packet = net.minecraft.network.protocol.game.ClientboundLevelEventPacket.class.cast(decodedPacket);
        return dev.voldechse.replayframework.adapter.paper.packet.clientbound.CaptureContexts.atBlockPosition(
                base,
                new dev.voldechse.replayframework.api.recording.BlockPosition(
                        packet.getPos().getX(), packet.getPos().getY(), packet.getPos().getZ()));
    }

    /** A global event is placed relative to each recipient; type and data name it. A local event is one packet object. */
    @Override
    public RecipientCopies recipientCopies() {
        return RecipientCopies.keyedBy(
                net.minecraft.network.protocol.game.ClientboundLevelEventPacket.class,
                packet -> packet.isGlobalEvent()
                        ? java.util.List.of(packet.getType(), packet.getData(), true)
                        : java.util.List.of(packet.getType(), packet.getPos(), packet.getData(), false));
    }
}
