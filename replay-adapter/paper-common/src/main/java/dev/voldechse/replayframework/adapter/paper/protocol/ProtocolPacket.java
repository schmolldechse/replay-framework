package dev.voldechse.replayframework.adapter.paper.protocol;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.Objects;

/**
 * One packet of a {@link ProtocolSnapshot}.
 *
 * @param phase connection phase
 * @param direction packet direction
 * @param typeName {@code <phase>.<direction>.<identifier>}
 * @param packetId the live numeric ID in its phase
 * @param codecKey the adapter's codec key for this packet
 */
public record ProtocolPacket(
        PacketPhase phase,
        PacketDescriptor.Direction direction,
        String typeName,
        int packetId,
        String codecKey) {

    public ProtocolPacket {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(direction, "direction");
        typeName = ProtocolSnapshot.requireStableText(typeName, "typeName");
        if (packetId < 0) {
            throw new IncompatibleAdapterException("snapshot packet ID is negative");
        }
        codecKey = ProtocolSnapshot.requireStableText(codecKey, "codecKey");
    }
}
