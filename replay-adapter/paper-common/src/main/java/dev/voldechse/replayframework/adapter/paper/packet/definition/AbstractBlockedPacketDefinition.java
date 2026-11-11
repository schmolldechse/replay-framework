package dev.voldechse.replayframework.adapter.paper.packet.definition;

import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;

/**
 * Captured PLAY transport data that must not enter packet-local playback.
 *
 * <p>The control classification keeps the original frame available for stream
 * validation and diagnostics while making it non-replayable, non-checkpoint
 * data with an unsafe playback scope. {@link PacketDefinition} therefore
 * rejects the descriptor before invoking a codec or a rewrite hook.</p>
 *
 * @param <C> playback-session context type
 */
public abstract class AbstractBlockedPacketDefinition<C> extends PacketDefinition<C> {

    protected AbstractBlockedPacketDefinition(int packetId, String identifier) {
        super(packetId, identifier, PacketClassification.control());
    }
}
