package dev.voldechse.replayframework.core.diagnostics;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, internal read model for runtime diagnostics. */
public record ReplayDiagnosticsSnapshot(
        Instant capturedAt,
        List<RecordingState> recordings,
        List<PlaybackState> playbacks,
        long queuedBytes,
        long packetsPerSecond,
        long cacheHits,
        long cacheMisses,
        long pendingUploadBytes,
        Optional<Duration> lastStorageLatency) {

    /** Validates and defensively copies the complete snapshot. */
    public ReplayDiagnosticsSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        recordings = sortedCopy(recordings, Comparator.comparing(state -> state.sessionId().value()));
        playbacks = sortedCopy(playbacks, Comparator.comparing(state -> state.sessionId().value()));
        queuedBytes = nonNegative(queuedBytes, "queuedBytes");
        packetsPerSecond = nonNegative(packetsPerSecond, "packetsPerSecond");
        cacheHits = nonNegative(cacheHits, "cacheHits");
        cacheMisses = nonNegative(cacheMisses, "cacheMisses");
        pendingUploadBytes = nonNegative(pendingUploadBytes, "pendingUploadBytes");
        Objects.requireNonNull(lastStorageLatency, "lastStorageLatency");
        lastStorageLatency.ifPresent(value -> {
            if (value.isNegative()) {
                throw new IllegalArgumentException("lastStorageLatency must not be negative");
            }
        });
    }

    private static <T> List<T> sortedCopy(List<T> values, Comparator<T> comparator) {
        Objects.requireNonNull(values, "values");
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, "snapshot entry"));
        }
        copy.sort(comparator);
        return List.copyOf(copy);
    }

    private static long nonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /** Immutable state of one active recording. */
    public record RecordingState(
            RecordingSessionId sessionId,
            ReplayId replayId,
            RecordingStatus status,
            Duration duration,
            long totalBytes,
            long packetCount,
            long queuedBytes) {

        /** Validates identifiers, lifecycle state, duration and counters. */
        public RecordingState {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be negative");
            }
            nonNegative(totalBytes, "totalBytes");
            nonNegative(packetCount, "packetCount");
            nonNegative(queuedBytes, "queuedBytes");
        }
    }

    /** Immutable state of one active playback viewer. */
    public record PlaybackState(
            PlaybackSessionId sessionId,
            ReplayId replayId,
            UUID viewerId,
            PlaybackSnapshot snapshot) {

        /** Validates all immutable playback identity and timeline values. */
        public PlaybackState {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(replayId, "replayId");
            Objects.requireNonNull(viewerId, "viewerId");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
