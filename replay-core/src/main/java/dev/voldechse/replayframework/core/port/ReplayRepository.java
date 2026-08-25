package dev.voldechse.replayframework.core.port;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Internal asynchronous persistence port for replay catalog rows.
 *
 * <p>This interface is an implementation boundary between core orchestration and a database
 * adapter. It is not part of the public framework API.</p>
 */
public interface ReplayRepository {

    /** Creates a replay in {@link RecordingStatus#INITIALIZING}. */
    CompletionStage<ReplayRow> create(ReplayCreate command);

    /** Loads one replay row without exposing its Hibernate entity. */
    CompletionStage<Optional<ReplayRow>> find(ReplayId replayId);

    /** Executes the supported catalog query and returns an immutable page. */
    CompletionStage<ReplayPage<ReplayRow>> page(ReplayQuery query);

    /** Applies one compare-and-transition lifecycle operation atomically. */
    CompletionStage<ReplayRow> transition(ReplayTransition command);

    /** Deletes catalog data after the replay has entered {@link RecordingStatus#DELETING}. */
    CompletionStage<Void> delete(ReplayId replayId);

    /** Immutable input for creating a replay catalog row. */
    record ReplayCreate(
            ReplayId replayId,
            String title,
            String description,
            String adapterId,
            int protocolVersion,
            int formatRevision,
            ReplayStorageBackend storageBackend,
            String storageKey,
            Instant createdAt,
            JsonObject metadata) {

        /** Validates values that must never reach a database constraint as a surprise. */
        public ReplayCreate {
            Objects.requireNonNull(replayId, "replayId");
            requireNonBlank(title, "title");
            Objects.requireNonNull(description, "description");
            requireNonBlank(adapterId, "adapterId");
            if (protocolVersion < 0) {
                throw new IllegalArgumentException("protocolVersion must not be negative");
            }
            if (formatRevision < 0) {
                throw new IllegalArgumentException("formatRevision must not be negative");
            }
            Objects.requireNonNull(storageBackend, "storageBackend");
            requireNonBlank(storageKey, "storageKey");
            Objects.requireNonNull(createdAt, "createdAt");
            metadata = copyObject(metadata, "metadata");
        }

        /** Returns a defensive copy of the mutable Gson tree. */
        @Override
        public JsonObject metadata() {
            return metadata.deepCopy();
        }
    }

    /** Immutable replay metrics update. */
    record ReplayMetrics(
            long durationNanos,
            long totalBytes,
            long packetCount,
            long segmentCount,
            long checkpointCount) {

        /** Rejects negative counters before a transaction is opened. */
        public ReplayMetrics {
            requireNonNegative(durationNanos, "durationNanos");
            requireNonNegative(totalBytes, "totalBytes");
            requireNonNegative(packetCount, "packetCount");
            requireNonNegative(segmentCount, "segmentCount");
            requireNonNegative(checkpointCount, "checkpointCount");
        }
    }

    /** Failure payload required for a transition into FAILED. */
    record FailureDetails(ReplayFailureCode code, String description, Instant completedAt) {

        /** Validates a safe, non-blank failure diagnostic. */
        public FailureDetails {
            Objects.requireNonNull(code, "code");
            requireNonBlank(description, "description");
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    /** Immutable compare-and-transition request. */
    record ReplayTransition(
            ReplayId replayId,
            RecordingStatus expectedStatus,
            RecordingStatus nextStatus,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            Optional<ReplayCompletionReason> completionReason,
            Optional<FailureDetails> failure,
            Optional<ReplayMetrics> metrics) {

        /** Validates the lifecycle edge and transition-specific required payloads. */
        public ReplayTransition {
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(expectedStatus, "expectedStatus");
            Objects.requireNonNull(nextStatus, "nextStatus");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(completionReason, "completionReason");
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(metrics, "metrics");
            if (!isAllowedTransition(expectedStatus, nextStatus)) {
                throw new IllegalArgumentException(
                        "invalid replay status transition: " + expectedStatus + " -> " + nextStatus);
            }
            if (nextStatus == RecordingStatus.RECORDING && startedAt.isEmpty()) {
                throw new IllegalArgumentException("RECORDING requires startedAt");
            }
            if (nextStatus == RecordingStatus.AVAILABLE
                    && (completionReason.isEmpty() || completedAt.isEmpty() || metrics.isEmpty())) {
                throw new IllegalArgumentException(
                        "AVAILABLE requires completionReason, completedAt and metrics");
            }
            if (nextStatus == RecordingStatus.FAILED && failure.isEmpty()) {
                throw new IllegalArgumentException("FAILED requires failure details");
            }
            if (nextStatus != RecordingStatus.AVAILABLE && completionReason.isPresent()) {
                throw new IllegalArgumentException("completionReason is only valid for AVAILABLE");
            }
            if (nextStatus != RecordingStatus.FAILED && failure.isPresent()) {
                throw new IllegalArgumentException("failure details are only valid for FAILED");
            }
        }

        private static boolean isAllowedTransition(
                RecordingStatus expectedStatus, RecordingStatus nextStatus) {
            return switch (expectedStatus) {
                case INITIALIZING -> nextStatus == RecordingStatus.RECORDING
                        || nextStatus == RecordingStatus.FAILED;
                case RECORDING -> nextStatus == RecordingStatus.FINALIZING
                        || nextStatus == RecordingStatus.FAILED;
                case FINALIZING -> nextStatus == RecordingStatus.AVAILABLE
                        || nextStatus == RecordingStatus.FAILED;
                case AVAILABLE -> nextStatus == RecordingStatus.DELETING;
                case FAILED, DELETING -> false;
            };
        }
    }

    /** Immutable replay catalog read model. */
    record ReplayRow(
            ReplayId replayId,
            String title,
            String description,
            RecordingStatus status,
            Optional<ReplayCompletionReason> completionReason,
            Optional<ReplayFailureCode> failureCode,
            Optional<String> failureDescription,
            String adapterId,
            int protocolVersion,
            int formatRevision,
            ReplayStorageBackend storageBackend,
            String storageKey,
            long durationNanos,
            long totalBytes,
            long packetCount,
            long segmentCount,
            long checkpointCount,
            Instant createdAt,
            Optional<Instant> startedAt,
            Optional<Instant> completedAt,
            JsonObject metadata,
            long metadataRevision,
            long entityVersion) {

        /** Validates and copies database values before returning them to core. */
        public ReplayRow {
            Objects.requireNonNull(replayId, "replayId");
            requireNonBlank(title, "title");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(completionReason, "completionReason");
            Objects.requireNonNull(failureCode, "failureCode");
            Objects.requireNonNull(failureDescription, "failureDescription");
            requireNonBlank(adapterId, "adapterId");
            if (protocolVersion < 0 || formatRevision < 0) {
                throw new IllegalArgumentException("version values must not be negative");
            }
            Objects.requireNonNull(storageBackend, "storageBackend");
            requireNonBlank(storageKey, "storageKey");
            requireNonNegative(durationNanos, "durationNanos");
            requireNonNegative(totalBytes, "totalBytes");
            requireNonNegative(packetCount, "packetCount");
            requireNonNegative(segmentCount, "segmentCount");
            requireNonNegative(checkpointCount, "checkpointCount");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            metadata = copyObject(metadata, "metadata");
            requireNonNegative(metadataRevision, "metadataRevision");
            requireNonNegative(entityVersion, "entityVersion");
        }

        /** Returns a defensive copy of the mutable Gson tree. */
        @Override
        public JsonObject metadata() {
            return metadata.deepCopy();
        }
    }

    /** Indicates that the requested replay does not exist. */
    final class ReplayNotFoundException extends RuntimeException {
        public ReplayNotFoundException(ReplayId replayId) {
            super("replay not found: " + replayId);
        }
    }

    /** Indicates that the expected status no longer matches the persisted status. */
    final class StatusTransitionConflictException extends RuntimeException {
        public StatusTransitionConflictException(ReplayId replayId, RecordingStatus expected) {
            super("replay status transition conflict for " + replayId + "; expected " + expected);
        }
    }

    /** Indicates that a query contains metadata criteria not supported by this compiler. */
    final class MetadataQueryUnavailableException extends RuntimeException {
        public MetadataQueryUnavailableException() {
            super("custom metadata query compilation is not available in this persistence stage");
        }
    }

    private static JsonObject copyObject(JsonObject value, String name) {
        return Objects.requireNonNull(value, name).deepCopy();
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
