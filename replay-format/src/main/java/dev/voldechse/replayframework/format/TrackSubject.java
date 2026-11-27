package dev.voldechse.replayframework.format;

import java.util.Objects;
import java.util.UUID;

/**
 * Something with state of its own that a {@link TrackKind#STATE} track
 * records: a player, or an entity with an inventory.
 *
 * @param kind what the subject is
 * @param id the subject's UUID: a player's profile ID or an entity's UUID
 */
public record TrackSubject(Kind kind, UUID id) {

    /** Validates the subject identity. */
    public TrackSubject {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /** Creates the subject of a player. */
    public static TrackSubject player(UUID profileId) {
        return new TrackSubject(Kind.PLAYER, profileId);
    }

    /** Creates the subject of an entity. */
    public static TrackSubject entity(UUID entityId) {
        return new TrackSubject(Kind.ENTITY, entityId);
    }

    /**
     * Kinds of subjects. Block entities are a planned kind; they are
     * identified by place rather than UUID and get their own identity then.
     */
    public enum Kind {
        /** A player, identified by its profile UUID. */
        PLAYER,
        /** A non-player entity, identified by its entity UUID. */
        ENTITY
    }
}
