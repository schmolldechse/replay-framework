package dev.voldechse.replayframework.format;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What identifies one track within a replay: its kind and the world or
 * subject the kind is keyed by. A replay has at most one track per key.
 *
 * @param kind the kind of the track
 * @param world the world, for a kind keyed by world
 * @param subject the subject, for a kind keyed by subject
 */
public record TrackKey(TrackKind kind, Optional<String> world, Optional<TrackSubject> subject) {

    /** Validates that the key matches what the kind is keyed by. */
    public TrackKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(subject, "subject");
        String name = kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        switch (kind.keyedBy()) {
            case NOTHING -> {
                if (world.isPresent() || subject.isPresent()) {
                    throw new IllegalArgumentException("a " + name + " track has no world and no subject");
                }
            }
            case WORLD -> {
                if (world.isEmpty() || subject.isPresent()) {
                    throw new IllegalArgumentException("a " + name + " track has a world and no subject");
                }
                RawPacketFrame.requireWorldKey(world.get());
            }
            case SUBJECT -> {
                if (subject.isEmpty() || world.isPresent()) {
                    throw new IllegalArgumentException("a " + name + " track has a subject and no world");
                }
            }
        }
    }

    /** The global track. */
    public static TrackKey global() {
        return new TrackKey(TrackKind.GLOBAL, Optional.empty(), Optional.empty());
    }

    /** The scene track of a world. */
    public static TrackKey world(String world) {
        return new TrackKey(TrackKind.WORLD, Optional.of(world), Optional.empty());
    }

    /** The scene track of a frame world: the world's track, or the global track for none. */
    public static TrackKey scene(Optional<String> world) {
        return world.map(TrackKey::world).orElseGet(TrackKey::global);
    }

    /** The state track of a subject. */
    public static TrackKey state(TrackSubject subject) {
        return new TrackKey(TrackKind.STATE, Optional.empty(), Optional.of(subject));
    }

    /** The block state track of a world. */
    public static TrackKey blockState(String world) {
        return new TrackKey(TrackKind.BLOCK_STATE, Optional.of(world), Optional.empty());
    }

    /**
     * The descriptor of the track with this key.
     *
     * @param id the track's ID
     * @param label display name, for example a player's name; may be empty
     */
    public TrackDescriptor describe(int id, String label) {
        return new TrackDescriptor(id, kind, world, subject, label);
    }
}
