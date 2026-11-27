package dev.voldechse.replayframework.api.playback;

import java.util.Objects;
import java.util.UUID;

/**
 * Something in a replay with state of its own: a player, or an entity with an
 * inventory no client receives without opening it.
 *
 * @param kind what the subject is
 * @param id a player's profile UUID or an entity's UUID
 */
public record RecordedSubject(Kind kind, UUID id) {

    public RecordedSubject {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    /** The subject of a player. */
    public static RecordedSubject player(UUID profileId) {
        return new RecordedSubject(Kind.PLAYER, profileId);
    }

    /** The subject of an entity. */
    public static RecordedSubject entity(UUID entityId) {
        return new RecordedSubject(Kind.ENTITY, entityId);
    }

    /** Kinds of subjects. */
    public enum Kind {
        /** A player, identified by its profile UUID. */
        PLAYER,
        /** A non-player entity, identified by its entity UUID. */
        ENTITY
    }
}
