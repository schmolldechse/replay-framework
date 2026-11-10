package dev.voldechse.replayframework.prototype.packet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit immutable packet-ID lookup; production binding would cover all PLAY packets. */
public final class PacketHandlerCatalog {

    private final Map<Integer, AbstractClientboundPacketHandler<?, ?>> byPacketId;

    public PacketHandlerCatalog(List<AbstractClientboundPacketHandler<?, ?>> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        Map<Integer, AbstractClientboundPacketHandler<?, ?>> indexed = new HashMap<>();
        for (AbstractClientboundPacketHandler<?, ?> handler : List.copyOf(handlers)) {
            Objects.requireNonNull(handler, "handlers contains null");
            AbstractClientboundPacketHandler<?, ?> duplicate = indexed.putIfAbsent(
                    handler.spec().packetId(), handler);
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "duplicate packet ID " + handler.spec().packetId() + " for "
                                + duplicate.spec().typeName() + " and " + handler.spec().typeName());
            }
        }
        this.byPacketId = Map.copyOf(indexed);
    }

    public PlaybackResult handle(
            RawPacketFrame frame,
            PacketCodecSession codec,
            PlaybackContext context) {
        AbstractClientboundPacketHandler<?, ?> handler = byPacketId.get(frame.packetId());
        if (handler == null) {
            return PlaybackResult.fail("no Paper 26.3 packet handler is bound for ID "
                    + frame.packetId());
        }
        return handler.handle(frame, codec, context);
    }

    /** Minimal prototype catalog. The productive catalog would be explicit and complete. */
    public static PacketHandlerCatalog addEntityOnly(int addEntityPacketId) {
        return new PacketHandlerCatalog(List.of(
                new AddEntityPacketHandler(addEntityPacketId)));
    }
}
