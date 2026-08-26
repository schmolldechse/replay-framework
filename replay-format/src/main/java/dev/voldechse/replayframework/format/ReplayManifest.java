package dev.voldechse.replayframework.format;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
 * @param files published replay artifacts
 */
public record ReplayManifest(
        String replayId,
        String adapterId,
        int protocolVersion,
        String registryFingerprint,
        int formatRevision,
        long durationNanos,
        List<ArtifactFile> files) {

    public static final int CURRENT_FORMAT_REVISION = 3;

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
        Objects.requireNonNull(files, "files");
        Set<String> paths = new HashSet<>();
        for (ArtifactFile file : files) {
            Objects.requireNonNull(file, "files contains null");
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("duplicate manifest artifact path: " + file.path());
            }
        }
        files = files.stream()
                .sorted(Comparator.comparing(ArtifactFile::path))
                .toList();
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

        private static void validateTypePath(ArtifactType type, String path) {
            boolean valid = switch (type) {
                case INDEX -> path.equals("index.bin");
                case SEGMENT -> path.startsWith("segments/") && path.endsWith(".segment")
                        && path.length() > "segments/.segment".length();
                case CHECKPOINT -> path.startsWith("checkpoints/") && path.endsWith(".checkpoint")
                        && path.length() > "checkpoints/.checkpoint".length();
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
