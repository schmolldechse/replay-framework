package dev.voldechse.replayframework.format;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable replay commit metadata and its published artifact inventory.
 *
 * @param replayId canonical UUID string of the replay
 * @param adapterId adapter that produced the replay
 * @param protocolVersion Minecraft protocol version
 * @param registryFingerprint adapter registry fingerprint
 * @param formatRevision manifest format revision
 * @param durationNanos replay duration in nanoseconds
 * @param tracks the tracks of the replay, ordered by ID
 * @param files published replay artifacts; every file belongs to a track
 */
public record ReplayManifest(
        String replayId,
        String adapterId,
        int protocolVersion,
        String registryFingerprint,
        int formatRevision,
        long durationNanos,
        List<TrackDescriptor> tracks,
        List<ArtifactFile> files) {

    /**
     * Must equal the adapter format revision: recording, finalization and
     * playback all compare the adapter descriptor revision with this value.
     * Revision 5 stores a replay as tracks; see {@link TrackDescriptor}.
     */
    public static final int CURRENT_FORMAT_REVISION = 5;

    /** Validates and canonicalizes the replay manifest. */
    public ReplayManifest {
        replayId = canonicalUuid(replayId);
        adapterId = requireNonEmpty(adapterId, "adapterId");
        if (protocolVersion < 0) {
            throw new IllegalArgumentException("protocolVersion must not be negative");
        }
        registryFingerprint = requireNonEmpty(registryFingerprint, "registryFingerprint");
        if (formatRevision != CURRENT_FORMAT_REVISION) {
            throw new IllegalArgumentException("unsupported manifest format revision: " + formatRevision);
        }
        if (durationNanos < 0L) {
            throw new IllegalArgumentException("durationNanos must not be negative");
        }
        tracks = validateTracks(tracks);
        Objects.requireNonNull(files, "files");
        Set<String> paths = new HashSet<>();
        Map<Integer, Integer> indexes = new HashMap<>();
        Set<Integer> trackIds = new HashSet<>();
        tracks.forEach(track -> trackIds.add(track.id()));
        for (ArtifactFile file : files) {
            Objects.requireNonNull(file, "files contains null");
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("duplicate manifest artifact path: " + file.path());
            }
            if (!trackIds.contains(file.trackId())) {
                throw new IllegalArgumentException("artifact belongs to no track: " + file.path());
            }
            if (file.type() == ArtifactType.INDEX) {
                indexes.merge(file.trackId(), 1, Integer::sum);
            }
        }
        for (TrackDescriptor track : tracks) {
            if (indexes.getOrDefault(track.id(), 0) != 1) {
                throw new IllegalArgumentException("track " + track.id() + " needs exactly one index");
            }
        }
        files = files.stream()
                .sorted(Comparator.comparing(ArtifactFile::path))
                .toList();
    }

    /** Returns the track with the given ID. */
    public Optional<TrackDescriptor> track(int id) {
        return tracks.stream().filter(track -> track.id() == id).findFirst();
    }

    /** Returns the files of one track, ordered by path. */
    public List<ArtifactFile> files(TrackDescriptor track) {
        Objects.requireNonNull(track, "track");
        return files.stream().filter(file -> file.trackId() == track.id()).toList();
    }

    private static List<TrackDescriptor> validateTracks(List<TrackDescriptor> tracks) {
        Objects.requireNonNull(tracks, "tracks");
        Set<Integer> ids = new HashSet<>();
        Set<String> worlds = new HashSet<>();
        Set<TrackSubject> subjects = new HashSet<>();
        Set<String> blockStates = new HashSet<>();
        int globals = 0;
        for (TrackDescriptor track : tracks) {
            Objects.requireNonNull(track, "tracks contains null");
            if (!ids.add(track.id())) {
                throw new IllegalArgumentException("duplicate track id: " + track.id());
            }
            switch (track.kind()) {
                case GLOBAL -> globals++;
                case WORLD -> {
                    if (!worlds.add(track.world().orElseThrow())) {
                        throw new IllegalArgumentException("duplicate world track: " + track.world().get());
                    }
                }
                case STATE -> {
                    if (!subjects.add(track.subject().orElseThrow())) {
                        throw new IllegalArgumentException("duplicate state track: " + track.subject().get());
                    }
                }
                case BLOCK_STATE -> {
                    if (!blockStates.add(track.world().orElseThrow())) {
                        throw new IllegalArgumentException("duplicate block state track: " + track.world().get());
                    }
                }
            }
        }
        if (globals > 1) {
            throw new IllegalArgumentException("a replay has at most one global track");
        }
        return tracks.stream().sorted(Comparator.comparingInt(TrackDescriptor::id)).toList();
    }

    /** Artifact categories addressable by the manifest. */
    public enum ArtifactType {
        /** Uncompressed binary seek index. */
        INDEX,
        /** Compressed packet segment. */
        SEGMENT,
        /** Compressed checkpoint packet bundle. */
        CHECKPOINT
    }

    /**
     * Immutable file metadata and integrity value.
     *
     * @param type artifact category
     * @param path canonical relative artifact path
     * @param sizeBytes exact stored byte size
     * @param sha256 lowercase SHA-256 digest of the stored bytes
     */
    public record ArtifactFile(
            ArtifactType type,
            String path,
            long sizeBytes,
            String sha256) {

        /** Validates artifact path and digest invariants. */
        public ArtifactFile {
            Objects.requireNonNull(type, "type");
            path = canonicalPath(path);
            if (sizeBytes < 0L) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must contain 64 lowercase hex characters");
            }
            validateTypePath(type, path);
        }

        /** The ID of the track the file belongs to: {@code tracks/<id>/...}. */
        public int trackId() {
            return trackIdOf(path);
        }

        /** The path of the file inside its track directory. */
        public String trackPath() {
            return path.substring(path.indexOf('/', TRACKS.length()) + 1);
        }

        private static final String TRACKS = "tracks/";

        private static int trackIdOf(String path) {
            int end = path.indexOf('/', TRACKS.length());
            if (!path.startsWith(TRACKS) || end < 0) {
                throw new IllegalArgumentException("artifact path is not inside a track: " + path);
            }
            String id = path.substring(TRACKS.length(), end);
            if (!id.matches("0|[1-9][0-9]{0,8}")) {
                throw new IllegalArgumentException("artifact path has an invalid track id: " + path);
            }
            return Integer.parseInt(id);
        }

        private static void validateTypePath(ArtifactType type, String path) {
            trackIdOf(path);
            String local = path.substring(path.indexOf('/', TRACKS.length()) + 1);
            boolean valid = switch (type) {
                case INDEX -> local.equals("index.bin");
                case SEGMENT -> local.startsWith("segments/") && local.endsWith(".segment")
                        && local.length() > "segments/.segment".length()
                        && local.indexOf('/', "segments/".length()) < 0;
                case CHECKPOINT -> local.startsWith("checkpoints/") && local.endsWith(".checkpoint")
                        && local.length() > "checkpoints/.checkpoint".length()
                        && local.indexOf('/', "checkpoints/".length()) < 0;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "artifact path does not match its type: " + type + " / " + path);
            }
        }
    }

    private static String canonicalUuid(String value) {
        Objects.requireNonNull(value, "replayId");
        try {
            UUID uuid = UUID.fromString(value);
            String canonical = uuid.toString();
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException("replayId must be a canonical UUID string");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("replayId must be a canonical UUID string", exception);
        }
    }

    private static String requireNonEmpty(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }

    private static String canonicalPath(String value) {
        Objects.requireNonNull(value, "path");
        if (value.isEmpty() || value.startsWith("/") || value.startsWith("\\")
                || value.contains("\\")) {
            throw new IllegalArgumentException("artifact path must be relative and use '/' separators");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("artifact path contains an invalid segment: " + value);
            }
        }
        String canonical = String.join("/", segments);
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("artifact path is not canonical: " + value);
        }
        return canonical;
    }
}
