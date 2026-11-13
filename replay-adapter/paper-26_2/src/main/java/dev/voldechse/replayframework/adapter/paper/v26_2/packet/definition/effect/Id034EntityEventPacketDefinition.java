package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.RebuildingPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.ValueRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.EntityEvent;

/**
 * Complete read/rewrite/rebuild contract for {@code minecraft:entity_event}.
 *
 * <p>The event byte is interpreted by the target entity type, and the protocol
 * leaves gaps and future values available. Preserve the full unsigned byte
 * rather than restricting it to the vanilla constants. The player debug-info
 * and permission-level statuses (22 through 28) describe the recorded
 * connection's local player settings, so drop those during playback to keep
 * the observer's connection state unchanged.</p>
 */
final class Id034EntityEventPacketDefinition extends RebuildingPacketDefinition<
        Paper26_2ReplayPacketRewriter,
        ClientboundEntityEventPacket,
        Id034EntityEventPacketDefinition.Value> {

    static final int PACKET_ID = 0x22;
    static final String IDENTIFIER = "minecraft:entity_event";

    Id034EntityEventPacketDefinition() {
        super(
                PACKET_ID,
                IDENTIFIER,
                PacketClassification.ephemeral(
                        PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES),
                ClientboundEntityEventPacket.class);
    }

    @Override
    protected Value read(ClientboundEntityEventPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            // Paper 26.2 has no public entity-ID getter for this packet. Its
            // native codec is the version-bound source of its Int + Byte wire fields.
            ClientboundEntityEventPacket.STREAM_CODEC.encode(buffer, packet);
            return new Value(buffer.readInt(), buffer.readUnsignedByte());
        } finally {
            buffer.release();
        }
    }

    @Override
    protected ValueRewriteResult<Value> rewrite(
            Value value,
            Paper26_2ReplayPacketRewriter context) {
        if (isConnectionLocalStatus(value.eventStatus())) {
            return ValueRewriteResult.drop(
                    "entity_event player debug/permission status belongs to the recorded connection");
        }
        return ValueRewriteResult.send(new Value(
                context.mapEntityId(value.entityId()), value.eventStatus()));
    }

    @Override
    protected ClientboundEntityEventPacket rebuild(Value value) {
        // The native type exposes no constructor for an already rewritten ID.
        // Its codec preserves the exact 26.2 Int + Byte packet contract.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(value.entityId());
            buffer.writeByte(value.eventStatus());
            return ClientboundEntityEventPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static boolean isConnectionLocalStatus(int eventStatus) {
        return switch (eventStatus) {
            case EntityEvent.REDUCED_DEBUG_INFO,
                    EntityEvent.FULL_DEBUG_INFO,
                    EntityEvent.PERMISSION_LEVEL_ALL,
                    EntityEvent.PERMISSION_LEVEL_MODERATORS,
                    EntityEvent.PERMISSION_LEVEL_GAMEMASTERS,
                    EntityEvent.PERMISSION_LEVEL_ADMINS,
                    EntityEvent.PERMISSION_LEVEL_OWNERS -> true;
            default -> false;
        };
    }

    record Value(int entityId, int eventStatus) {

        Value {
            if (eventStatus < 0 || eventStatus > 0xFF) {
                throw new IncompatibleAdapterException(
                        "entity_event status is not an unsigned byte: " + eventStatus);
            }
        }
    }
}
