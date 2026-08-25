package dev.voldechse.replayframework.core.playback.cache;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.format.ReplayManifest;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Internal asynchronous cache contract for immutable replay segments. */
public interface SegmentCache {

    /**
     * Ensures that one verified segment is available for decoding.
     *
     * @param replay verified replay handle
     * @param segment segment metadata from the verified manifest
     * @return lease for the complete local segment file
     */
    CompletionStage<CacheEntryLease> ensureAvailable(
            ReplayArtifactReader.VerifiedReplay replay,
            SegmentRef segment);

    /** Immutable segment identity and time span used by the playback buffer. */
    record SegmentRef(
            int ordinal,
            ReplayManifest.ArtifactFile artifact,
            long startNanos,
            long endNanos) {

        /** Validates segment identity and ordered time bounds. */
        public SegmentRef {
            if (ordinal < 0) {
                throw new IllegalArgumentException("ordinal must not be negative");
            }
            Objects.requireNonNull(artifact, "artifact");
            if (artifact.type() != ReplayManifest.ArtifactType.SEGMENT) {
                throw new IllegalArgumentException("artifact must be a segment");
            }
            if (startNanos < 0L || endNanos < startNanos) {
                throw new IllegalArgumentException("invalid segment time bounds");
            }
        }
    }

    /**
     * Ensures that a segment reference belongs to the supplied replay.
     *
     * @param replay replay whose manifest is authoritative
     * @param segment segment to validate
     * @throws IllegalArgumentException when the reference is not a manifest member
     */
    static void requireManifestMember(
            ReplayArtifactReader.VerifiedReplay replay,
            SegmentRef segment) {
        Objects.requireNonNull(replay, "replay");
        Objects.requireNonNull(segment, "segment");
        ReplayId replayId = replay.replayId();
        boolean member = replay.manifest().files().stream()
                .anyMatch(file -> file.equals(segment.artifact()));
        if (!member) {
            throw new IllegalArgumentException(
                    "segment is not a member of the verified replay: " + replayId);
        }
    }
}
