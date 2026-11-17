package dev.voldechse.replayframework.adapter.paper.packet.clientbound.definition;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;

/**
 * Captured PLAY transport data that must not enter packet-local playback.
 *
 * <p>The control classification keeps the original frame available for stream
 * validation and diagnostics while making it non-replayable, non-checkpoint
 * data with an unsafe playback scope. {@link ClientboundPacketDefinition} therefore
 * rejects the descriptor before invoking a codec or a rewrite hook.</p>
 *
 * @param <C> playback-session context type
 */
public abstract class AbstractBlockedPacketDefinition<C> extends ClientboundPacketDefinition<C> {

    protected AbstractBlockedPacketDefinition(int packetId, String identifier) {
        super(packetId, identifier, PacketClassification.control());
    }
}
