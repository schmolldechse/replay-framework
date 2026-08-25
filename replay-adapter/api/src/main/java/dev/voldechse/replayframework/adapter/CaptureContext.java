package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.key.Key;

/**
 * Adapter-supplied semantic facts for one captured packet.
 *
 * <p>The values are deliberately optional. An adapter must not invent a
 * location or decoded text when the concrete protocol packet does not expose
 * it safely. The core treats an absent value as unknown and never falls back
 * to packet-name or raw-payload heuristics.</p>
 *
 * @param disposition verified packet category, when known
 * @param world packet world, when the adapter can identify it
 * @param position exact packet position, when the packet carries one
 * @param normalizedChat normalized chat text, when the packet is chat
 * @param customPayloadChannel exact custom-payload channel, when applicable
 */
public record CaptureContext(
        Optional<PacketDisposition> disposition,
        Optional<Key> world,
        Optional<BlockPosition> position,
        Optional<String> normalizedChat,
        Optional<Key> customPayloadChannel) {

    /**
     * Convenience constructor for adapter code with a verified disposition.
     * Passing {@code null} is reserved for the same explicitly unknown
     * disposition represented by {@link #unknown()}.
     */
    public CaptureContext(
            PacketDisposition disposition,
            Optional<Key> world,
            Optional<BlockPosition> position,
            Optional<String> normalizedChat,
            Optional<Key> customPayloadChannel) {
        this(Optional.ofNullable(disposition), world, position, normalizedChat, customPayloadChannel);
    }

    /** Validates and freezes all optional semantic values. */
    public CaptureContext {
        disposition = requireOptional(disposition, "disposition");
        world = requireOptional(world, "world");
        position = requireOptional(position, "position");
        normalizedChat = requireOptional(normalizedChat, "normalizedChat");
        customPayloadChannel = requireOptional(customPayloadChannel, "customPayloadChannel");
    }

    /**
     * Returns a context for legacy fixtures or adapters that only provide raw
     * packet bytes. Every semantic value remains unknown by design.
     */
    public static CaptureContext unknown() {
        return new CaptureContext(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
