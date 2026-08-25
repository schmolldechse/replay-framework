package dev.voldechse.replayframework.core.diagnostics;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe, passive runtime diagnostics collector.
 *
 * <p>All callbacks are best effort. No callback performs I/O or calls back
 * into a recording, playback, storage or database service.</p>
 */
public final class ReplayDiagnostics implements AutoCloseable {
    private final ConcurrentHashMap<RecordingSessionId, RecordingEntry> recordings =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PlaybackSessionId, PlaybackSession> playbacks =
            new ConcurrentHashMap<>();
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.ACTIVE);
    private final AtomicLong packetCount = new AtomicLong();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final AtomicLong pendingUploadBytes = new AtomicLong();
    private final AtomicReference<Duration> lastStorageLatency = new AtomicReference<>();
    private final AtomicReference<RateWindow> previousRateWindow = new AtomicReference<>();

    /** Registers or refreshes one recording observation. */
    public void recordingStarted(RecordingSession session) {
        if (acceptsRegistrations()) {
            putRecording(session, 0L);
        }
    }

    /** Refreshes one recording observation, including its current queue size. */
    public void recordingUpdated(RecordingSession session, long queuedBytes) {
        if (queuedBytes < 0L) {
            return;
        }
        if (acceptsRegistrations()) {
            putRecording(session, queuedBytes);
        } else if (session != null) {
            RecordingEntry existing = recordings.get(session.id());
            if (existing != null) {
                existing.queuedBytes.set(queuedBytes);
            }
        }
    }

    /** Removes one recording observation. */
    public void recordingFinished(RecordingSessionId sessionId) {
        if (sessionId != null) {
            recordings.remove(sessionId);
        }
    }

    /** Registers one playback observation. */
    public void playbackStarted(PlaybackSession session) {
        if (acceptsRegistrations() && session != null) {
            playbacks.putIfAbsent(session.id(), session);
        }
    }

    /** Refreshes one playback observation. */
    public void playbackUpdated(PlaybackSession session) {
        if (session == null) {
            return;
        }
        if (acceptsRegistrations()) {
            playbacks.put(session.id(), session);
        }
    }

    /** Removes one playback observation. */
    public void playbackFinished(PlaybackSessionId sessionId) {
        if (sessionId != null) {
            playbacks.remove(sessionId);
        }
    }

    /** Adds captured packet count without allowing arithmetic wraparound. */
    public void packetCaptured(long count) {
        if (count > 0L && phase.get() != Phase.CLOSED) {
            addSaturated(packetCount, count);
        }
    }

    /** Records one cache hit. */
    public void cacheHit() {
        if (phase.get() != Phase.CLOSED) {
            cacheHits.increment();
        }
    }

    /** Records one cache miss. */
    public void cacheMiss() {
        if (phase.get() != Phase.CLOSED) {
            cacheMisses.increment();
        }
    }

    /**
     * Starts measuring one storage operation. The returned handle is safe to
     * complete more than once; only its first terminal call is observed.
     */
    public StorageOperation beginStorageOperation(long pendingBytes) {
        if (pendingBytes < 0L) {
            throw new IllegalArgumentException("pendingBytes must not be negative");
        }
        if (phase.get() != Phase.ACTIVE) {
            return StorageOperation.NOOP;
        }
        addSaturated(this.pendingUploadBytes, pendingBytes);
        return new Operation(pendingBytes, System.nanoTime());
    }

    /** Stops accepting new session registrations while retaining the snapshot. */
    public void beginQuiesce() {
        phase.compareAndSet(Phase.ACTIVE, Phase.QUIESCING);
    }

    /** Stops all new observations; existing storage handles remain closable. */
    @Override
    public void close() {
        phase.set(Phase.CLOSED);
        recordings.clear();
        playbacks.clear();
    }

    /** Creates an immutable, non-blocking snapshot of the current counters. */
    public ReplayDiagnosticsSnapshot snapshot() {
        List<ReplayDiagnosticsSnapshot.RecordingState> recordingStates = new ArrayList<>();
        long queued = 0L;
        for (RecordingEntry entry : recordings.values()) {
            try {
                RecordingSession session = entry.session;
                RecordingStatus status = session.status();
                if (status == RecordingStatus.AVAILABLE
                        || status == RecordingStatus.FAILED
                        || status == RecordingStatus.DELETING) {
                    continue;
                }
                RecordingSession.Metrics metrics = session.metrics();
                long queueBytes = entry.queuedBytes.get();
                recordingStates.add(new ReplayDiagnosticsSnapshot.RecordingState(
                        session.id(),
                        session.replayId(),
                        status,
                        metrics.duration(),
                        metrics.totalBytes(),
                        metrics.packetCount(),
                        queueBytes));
                queued = addSaturated(queued, queueBytes);
            } catch (RuntimeException ignored) {
                // Diagnostics must never affect the owning lifecycle.
            }
        }

        List<ReplayDiagnosticsSnapshot.PlaybackState> playbackStates = new ArrayList<>();
        for (PlaybackSession session : playbacks.values()) {
            try {
                PlaybackSnapshot snapshot = session.snapshot();
                if (snapshot.status() != PlaybackStatus.CLOSED
                        && snapshot.status() != PlaybackStatus.FAILED) {
                    playbackStates.add(new ReplayDiagnosticsSnapshot.PlaybackState(
                            session.id(), session.replayId(), session.viewerId(), snapshot));
                }
            } catch (RuntimeException ignored) {
                // Diagnostics must never affect the owning lifecycle.
            }
        }

        return new ReplayDiagnosticsSnapshot(
                Instant.now(),
                recordingStates,
                playbackStates,
                queued,
                packetsPerSecond(),
                cacheHits.sum(),
                cacheMisses.sum(),
                pendingUploadBytes.get(),
                Optional.ofNullable(lastStorageLatency.get()));
    }

    private void putRecording(RecordingSession session, long queuedBytes) {
        if (session != null) {
            recordings.compute(session.id(), (ignored, existing) -> {
                if (existing == null || existing.session != session) {
                    return new RecordingEntry(session, queuedBytes);
                }
                existing.queuedBytes.set(queuedBytes);
                return existing;
            });
        }
    }

    private boolean acceptsRegistrations() {
        return phase.get() == Phase.ACTIVE;
    }

    private long packetsPerSecond() {
        long now = System.nanoTime();
        long current = packetCount.get();
        RateWindow previous = previousRateWindow.getAndSet(new RateWindow(now, current));
        if (previous == null || now <= previous.nanoTime || current < previous.packetCount) {
            return 0L;
        }
        long elapsed = now - previous.nanoTime;
        if (elapsed <= 0L) {
            return 0L;
        }
        long delta = current - previous.packetCount;
        if (delta > Long.MAX_VALUE / 1_000_000_000L) {
            return Long.MAX_VALUE;
        }
        return Math.min(Long.MAX_VALUE, (delta * 1_000_000_000L) / elapsed);
    }

    private void finishStorageOperation(long bytes, long startedNanos) {
        subtractSaturated(pendingUploadBytes, bytes);
        long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        lastStorageLatency.set(Duration.ofNanos(elapsed));
    }

    private static long addSaturated(AtomicLong target, long amount) {
        while (true) {
            long current = target.get();
            long next = current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
            if (target.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private static long addSaturated(long current, long amount) {
        return current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
    }

    private static void subtractSaturated(AtomicLong target, long amount) {
        while (true) {
            long current = target.get();
            long next = current < amount ? 0L : current - amount;
            if (target.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /** One-shot measurement handle for an asynchronous storage operation. */
    public interface StorageOperation extends AutoCloseable {
        StorageOperation NOOP = new StorageOperation() {
            @Override public void complete() { }
            @Override public void fail() { }
        };

        /** Marks the operation successful. */
        void complete();

        /** Marks the operation failed. */
        void fail();

        /** Treats close as a successful terminal measurement. */
        @Override
        default void close() {
            complete();
        }
    }

    private final class Operation implements StorageOperation {
        private final long pendingBytes;
        private final long startedNanos;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Operation(long pendingBytes, long startedNanos) {
            this.pendingBytes = pendingBytes;
            this.startedNanos = startedNanos;
        }

        @Override
        public void complete() {
            finish();
        }

        @Override
        public void fail() {
            finish();
        }

        private void finish() {
            if (completed.compareAndSet(false, true)) {
                finishStorageOperation(pendingBytes, startedNanos);
            }
        }
    }

    private record RecordingEntry(RecordingSession session, AtomicLong queuedBytes) {
        private RecordingEntry(RecordingSession session, long queuedBytes) {
            this(session, new AtomicLong(queuedBytes));
        }
    }

    private record RateWindow(long nanoTime, long packetCount) { }

    private enum Phase {
        ACTIVE,
        QUIESCING,
        CLOSED
    }
}
