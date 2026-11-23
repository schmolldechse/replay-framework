package dev.voldechse.replayframework.format;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * One track of a replay: a time-ordered stream of frames with its own
 * index, segments and checkpoints.
 *
 * <p>What all frames of a track share is stored here once instead of on
 * every frame: the world of a {@link TrackKind#WORLD} or
 * {@link TrackKind#BLOCK_STATE} track, the subject of a
 * {@link TrackKind#STATE} track, and the protocol phase, which is
 * {@link PacketPhase#PLAY} for every track.</p>
 *
 * @param id small, replay-unique identifier; also the directory name
 * @param kind what the track records
 * @param world the world of a {@code WORLD} or {@code BLOCK_STATE} track, empty otherwise
 * @param subject the subject of a {@code STATE} track, empty otherwise
 * @param label display name, for example a player's name; may be empty
 */
public record TrackDescriptor(
        int id,
        TrackKind kind,
        Optional<String> world,
        Optional<TrackSubject> subject,
        String label) {

    /** Longest label in UTF-8 bytes. */
    public static final int MAX_LABEL_LENGTH = 256;

    /** Validates that the key matches the kind. */
    public TrackDescriptor {
        if (id < 0) {
            throw new IllegalArgumentException("track id must not be negative");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(label, "label");
        if (label.getBytes(StandardCharsets.UTF_8).length > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("track label is too long");
        }
        switch (kind) {
            case GLOBAL -> {
                if (world.isPresent() || subject.isPresent()) {
                    throw new IllegalArgumentException("a global track has no world and no subject");
                }
            }
            case WORLD -> {
                if (world.isEmpty() || subject.isPresent()) {
                    throw new IllegalArgumentException("a world track has a world and no subject");
                }
                RawPacketFrame.requireWorldKey(world.get());
            }
            case STATE -> {
                if (subject.isEmpty() || world.isPresent()) {
                    throw new IllegalArgumentException("a state track has a subject and no world");
                }
            }
            case BLOCK_STATE -> {
                if (world.isEmpty() || subject.isPresent()) {
                    throw new IllegalArgumentException("a block state track has a world and no subject");
                }
                RawPacketFrame.requireWorldKey(world.get());
            }
        }
    }

    /** Creates the block state track of one world. */
    public static TrackDescriptor blockState(int id, String world) {
        return new TrackDescriptor(
                id, TrackKind.BLOCK_STATE, Optional.of(world), Optional.empty(), world);
    }

    /** Creates the global track. */
    public static TrackDescriptor global(int id) {
        return new TrackDescriptor(id, TrackKind.GLOBAL, Optional.empty(), Optional.empty(), "");
    }

    /** Creates the track of one world. */
    public static TrackDescriptor world(int id, String world) {
        return new TrackDescriptor(
                id, TrackKind.WORLD, Optional.of(world), Optional.empty(), world);
    }

    /** Creates the state track of one subject. */
    public static TrackDescriptor state(int id, TrackSubject subject, String label) {
        return new TrackDescriptor(
                id, TrackKind.STATE, Optional.empty(), Optional.of(subject), label);
    }

    /** The world every frame of this track belongs to; empty for global and subject state tracks. */
    public Optional<String> frameWorld() {
        return world;
    }

    /** The directory of the track's files, relative to the replay root. */
    public String directory() {
        return "tracks/" + id;
    }
}
