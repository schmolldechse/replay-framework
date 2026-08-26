package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Objects;

/** Paper 26.2 connection-bound implementation of the generic codec session. */
final class Paper26PacketCodec implements PacketCodec<Object> {
    private final PaperConnectionAccessor accessor;
    private final PaperConnectionAccessor.ConnectionHandle connection;
    private final PacketRegistry registry;

    Paper26PacketCodec(
            PaperConnectionAccessor accessor,
            PaperConnectionAccessor.ConnectionHandle connection,
            PacketRegistry registry) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public EncodedPacket encode(Object packet) {
        PaperConnectionAccessor.WirePacket encoded = accessor.inspectAndEncode(
                connection, packet, registry);
        return new EncodedPacket(
                encoded.phase(), encoded.direction(), encoded.packetId(), encoded.payload());
    }

    @Override
    public Object decode(RawPacketFrame frame) {
        return accessor.decodeReplayFrame(connection, frame, registry);
    }
}
