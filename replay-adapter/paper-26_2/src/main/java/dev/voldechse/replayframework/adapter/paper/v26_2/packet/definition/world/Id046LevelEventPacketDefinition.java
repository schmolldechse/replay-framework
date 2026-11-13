package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.world;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
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
final class Id046LevelEventPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundLevelEventPacket,
        Id046LevelEventPacketDefinition.Value> {

    static final int PACKET_ID = 0x2E;
    static final String IDENTIFIER = "minecraft:level_event";

    Id046LevelEventPacketDefinition() {
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
            Paper26_2ReplayPacketRewriter context) {
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
}
