package dev.voldechse.replayframework.core.port;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.MetadataRevisionOperation;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Internal asynchronous persistence port for current metadata and its mutation history. */
public interface MetadataRepository {

    /** Loads the current snapshot, including revision zero when no mutation exists. */
    CompletionStage<Optional<MetadataSnapshot>> current(ReplayId replayId);

    /** Loads only persisted mutation revisions in ascending order. */
    CompletionStage<List<MetadataRevisionRow>> history(ReplayId replayId);

    /** Applies a complete next snapshot and its revision in one transaction. */
    CompletionStage<MetadataSnapshot> apply(MetadataWrite command);

    /** Immutable current metadata read model used between core and the database adapter. */
    record MetadataSnapshot(
            ReplayId replayId,
            String title,
            String description,
            long revision,
            JsonObject values) {

        /** Validates and defensively copies the custom-value JSON object. */
        public MetadataSnapshot {
            Objects.requireNonNull(replayId, "replayId");
            requireNonBlank(title, "title");
            Objects.requireNonNull(description, "description");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            values = Objects.requireNonNull(values, "values").deepCopy();
        }

        /** Returns a defensive copy of the mutable Gson tree. */
        @Override
        public JsonObject values() {
            return values.deepCopy();
        }
    }

    /** Immutable persisted revision row. */
    record MetadataRevisionRow(
            long revision,
            Instant timestamp,
            MetadataRevisionOperation operation,
            MetadataSnapshot snapshot) {

        /** Validates append-only revision numbering and snapshot consistency. */
        public MetadataRevisionRow {
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

    /** Immutable atomic metadata write request. */
    record MetadataWrite(
            ReplayId replayId,
            long expectedRevision,
            MetadataSnapshot nextSnapshot,
            MetadataRevisionOperation operation,
            Instant committedAt) {

        /** Validates optimistic revision progression before opening a transaction. */
        public MetadataWrite {
            Objects.requireNonNull(replayId, "replayId");
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must not be negative");
            }
            Objects.requireNonNull(nextSnapshot, "nextSnapshot");
            if (!replayId.equals(nextSnapshot.replayId())) {
                throw new IllegalArgumentException("snapshot replayId must match write replayId");
            }
            if (nextSnapshot.revision() != expectedRevision + 1) {
                throw new IllegalArgumentException("nextSnapshot revision must be expectedRevision + 1");
            }
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(committedAt, "committedAt");
        }
    }

    /** Indicates that a replay does not exist for a metadata operation. */
    final class MetadataNotFoundException extends RuntimeException {
        public MetadataNotFoundException(ReplayId replayId) {
            super("metadata replay not found: " + replayId);
        }
    }

    /** Indicates that the observed metadata revision is stale. */
    final class MetadataRevisionConflictException extends RuntimeException {
        public MetadataRevisionConflictException(ReplayId replayId, long expectedRevision) {
            super("metadata revision conflict for " + replayId + "; expected " + expectedRevision);
        }
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
