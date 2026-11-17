package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 * @param origin source the packet was observed through
 * @param worldWide whether the packet concerns its whole world instead of a
 *                  place in it, such as a tab-list entry or public chat
 * @param observation the scene observation an {@link CaptureOrigin#OBSERVER}
 *                    packet belongs to; empty for every other origin
 * @param recipientCopy the event this packet is a copy of, when the server
 *                      sends the event to several players separately
 * @param subject the subject whose state the packet describes; such a packet
 *                belongs to the subject's state track, not to the scene
 */
public record CaptureContext(
        Optional<PacketDisposition> disposition,
        Optional<Key> world,
        Optional<BlockPosition> position,
        Optional<String> normalizedChat,
        Optional<Key> customPayloadChannel,
        CaptureOrigin origin,
        boolean worldWide,
        Optional<UUID> observation,
        Optional<RecipientCopy> recipientCopy,
        Optional<CaptureSubject> subject) {

    /** Creates a context for scene state observed through a player view. */
    public CaptureContext(
            Optional<PacketDisposition> disposition,
            Optional<Key> world,
            Optional<BlockPosition> position,
            Optional<String> normalizedChat,
            Optional<Key> customPayloadChannel) {
        this(disposition, world, position, normalizedChat, customPayloadChannel,
                CaptureOrigin.PLAYER_VIEW, false, Optional.empty(), Optional.empty(), Optional.empty());
    }

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
        Objects.requireNonNull(origin, "origin");
        observation = requireOptional(observation, "observation");
        recipientCopy = requireOptional(recipientCopy, "recipientCopy");
        subject = requireOptional(subject, "subject");
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

    /** Returns this context as observed through another source. */
    public CaptureContext withOrigin(CaptureOrigin nextOrigin) {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                nextOrigin, worldWide, observation, recipientCopy, subject);
    }

    /** Returns this context for a packet that concerns its whole world. */
    public CaptureContext asWorldWide() {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                origin, true, observation, recipientCopy, subject);
    }

    /**
     * Returns this context for a packet seen by the virtual observers of one
     * scene observation. Only the recording that opened it accepts the packet.
     */
    public CaptureContext observedBy(UUID observationId) {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                CaptureOrigin.OBSERVER, worldWide,
                Optional.of(Objects.requireNonNull(observationId, "observationId")),
                recipientCopy, subject);
    }

    /** Returns this context for one copy of an event sent to several players. */
    public CaptureContext asRecipientCopy(RecipientCopy copy) {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                origin, worldWide, observation,
                Optional.of(Objects.requireNonNull(copy, "copy")), subject);
    }

    /** Returns this context for a packet that describes the state of a subject. */
    public CaptureContext withSubject(CaptureSubject stateSubject) {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                origin, worldWide, observation, recipientCopy,
                Optional.of(Objects.requireNonNull(stateSubject, "stateSubject")));
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
