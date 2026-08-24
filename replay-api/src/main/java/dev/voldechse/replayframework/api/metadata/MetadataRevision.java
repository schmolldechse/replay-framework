package dev.voldechse.replayframework.api.metadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, complete snapshot of one committed metadata change.
 *
 * @param revision positive committed revision number
 * @param timestamp commit timestamp
 * @param operation mutation operation
 * @param snapshot complete metadata snapshot at this revision
 */
public record MetadataRevision(
        long revision,
        Instant timestamp,
        MetadataRevisionOperation operation,
        ReplayMetadata snapshot) {

    /** Validates revision numbering and snapshot consistency. */
    public MetadataRevision {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.revision() != revision) {
            throw new IllegalArgumentException("snapshot revision must match revision");
        }
    }
}
