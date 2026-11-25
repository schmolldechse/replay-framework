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
 * @param observation the scene observation an {@link CaptureOrigin#OBSERVER}
 *                    packet belongs to; empty for every other origin
 * @param recipientCopy the event this packet is a copy of, when the server
 *                      sends the event to several players separately
 * @param route the track the packet belongs to
 */
public record CaptureContext(
        Optional<PacketDisposition> disposition,
        Optional<Key> world,
        Optional<BlockPosition> position,
        Optional<String> normalizedChat,
        Optional<Key> customPayloadChannel,
        CaptureOrigin origin,
        Optional<UUID> observation,
        Optional<RecipientCopy> recipientCopy,
        TrackRoute route) {

    /** Creates a context for scene state observed through a player view. */
    public CaptureContext(
            Optional<PacketDisposition> disposition,
            Optional<Key> world,
            Optional<BlockPosition> position,
            Optional<String> normalizedChat,
            Optional<Key> customPayloadChannel) {
        this(disposition, world, position, normalizedChat, customPayloadChannel,
                CaptureOrigin.PLAYER_VIEW, Optional.empty(), Optional.empty(), TrackRoute.SCENE);
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
        Objects.requireNonNull(route, "route");
        if (route instanceof TrackRoute.BlockState && world.isEmpty()) {
            throw new IllegalArgumentException("block state needs a world");
        }
    }

    /** Whether the packet concerns its whole world instead of a place in it. */
    public boolean worldWide() {
        return route instanceof TrackRoute.WorldWide;
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
                nextOrigin, observation, recipientCopy, route);
    }

    /** Returns this context for a packet that concerns its whole world. */
    public CaptureContext asWorldWide() {
        return routedTo(TrackRoute.WORLD_WIDE);
    }

    /**
     * Returns this context for a packet seen by the virtual observers of one
     * scene observation. Only the recording that opened it accepts the packet.
     */
    public CaptureContext observedBy(UUID observationId) {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                CaptureOrigin.OBSERVER,
                Optional.of(Objects.requireNonNull(observationId, "observationId")),
                recipientCopy, route);
    }

    /** Returns this context for one copy of an event sent to several players. */
    public CaptureContext asRecipientCopy(RecipientCopy copy) {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                origin, observation,
                Optional.of(Objects.requireNonNull(copy, "copy")), route);
    }

    /** Returns this context for a packet that describes the state of a subject. */
    public CaptureContext withSubject(CaptureSubject stateSubject) {
        return routedTo(TrackRoute.subject(Objects.requireNonNull(stateSubject, "stateSubject")));
    }

    /**
     * Returns this context for a packet that carries the state of a block of
     * its world that the scene does not show, such as the contents of a chest.
     * Recordings keep it in the block state track of that world.
     */
    public CaptureContext asBlockState() {
        if (world.isEmpty()) {
            throw new IllegalStateException("block state needs a world");
        }
        return routedTo(TrackRoute.BLOCK_STATE);
    }

    /** Returns this context for another track; the route replaces the previous one. */
    public CaptureContext routedTo(TrackRoute nextRoute) {
        return new CaptureContext(
                disposition, world, position, normalizedChat, customPayloadChannel,
                origin, observation, recipientCopy, Objects.requireNonNull(nextRoute, "nextRoute"));
    }

    private static <T> Optional<T> requireOptional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }
}
