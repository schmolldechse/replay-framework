package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.capture.CaptureSink;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal session implementation shared by the public handle and one router
 * sink. All mutable state belongs to this session instance; overlapping
 * sessions never share queue, status, metric or failure references.
 */
final class DefaultRecordingSession implements RecordingSession, CaptureSink {
    private final RecordingSessionId id;
    private final ReplayId replayId;
    private final RecordingRequest request;
    private final ResolvedRecordingScope resolvedScope;
    private final ReplayCheckpoint initialCheckpoint;
    private final SessionPacketQueue queue;
    private final RecordingCoordinator coordinator;
    private final AtomicReference<RecordingStatus> status =
            new AtomicReference<>(RecordingStatus.INITIALIZING);
    private final AtomicReference<ReplayFailureCode> failureCode = new AtomicReference<>();
    private final AtomicReference<String> failureDescription = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<RecordingSession>> stopFuture =
            new AtomicReference<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicLong packetCount = new AtomicLong();
    private final AtomicLong segmentCount = new AtomicLong();
    private final AtomicLong checkpointCount = new AtomicLong(1L);
    private final AtomicLong recordingStartNanos = new AtomicLong();

    DefaultRecordingSession(
            RecordingSessionId id,
            ReplayId replayId,
            RecordingRequest request,
            ResolvedRecordingScope resolvedScope,
            ReplayCheckpoint initialCheckpoint,
            SessionPacketQueue queue,
            RecordingCoordinator coordinator) {
        this.id = Objects.requireNonNull(id, "id");
        this.replayId = Objects.requireNonNull(replayId, "replayId");
        this.request = Objects.requireNonNull(request, "request");
        this.resolvedScope = Objects.requireNonNull(resolvedScope, "resolvedScope");
        this.initialCheckpoint = Objects.requireNonNull(initialCheckpoint, "initialCheckpoint");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public RecordingSessionId id() {
        return id;
    }

    /** Internal alias used by CaptureRouter without exposing a new public API. */
    @Override
    public RecordingSessionId sessionId() {
        return id;
    }

    @Override
    public ReplayId replayId() {
        return replayId;
    }

    @Override
    public RecordingStatus status() {
        return status.get();
    }

    @Override
    public Metrics metrics() {
        long start = recordingStartNanos.get();
        long durationNanos = start == 0L ? 0L : elapsedSince(start);
        return new Metrics(
                Duration.ofNanos(durationNanos),
                totalBytes.get(),
                packetCount.get(),
                segmentCount.get(),
                checkpointCount.get());
    }

    @Override
    public Optional<ReplayFailureCode> failureCode() {
        return Optional.ofNullable(failureCode.get());
    }

    @Override
    public Optional<String> failureDescription() {
        return Optional.ofNullable(failureDescription.get());
    }

    @Override
    public CompletionStage<RecordingSession> stop() {
        CompletableFuture<RecordingSession> existing = stopFuture.get();
        if (existing != null) {
            return existing;
        }

        CompletableFuture<RecordingSession> created = new CompletableFuture<>();
        if (!stopFuture.compareAndSet(null, created)) {
            return stopFuture.get();
        }

        if (!status.compareAndSet(RecordingStatus.RECORDING, RecordingStatus.FINALIZING)) {
            created.complete(this);
            return created;
        }

        queue.closeForEnqueue();
        coordinator.unregister(id);
        try {
            coordinator.requestFinalization(this, ReplayCompletionReason.MANUAL)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            created.completeExceptionally(failure);
                        } else {
                            created.complete(result == null ? this : result);
                        }
                    });
        } catch (Throwable failure) {
            created.completeExceptionally(failure);
        }
        return created;
    }

    @Override
    public boolean accepts(CapturedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        return status.get() == RecordingStatus.RECORDING
                && !queue.closed()
                && resolvedScope.accepts(packet);
    }

    @Override
    public void enqueue(CapturedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (status.get() != RecordingStatus.RECORDING) {
            throw new IllegalStateException("recording session does not accept packets: " + status.get());
        }
        queue.enqueue(packet);
        packetCount.incrementAndGet();
        totalBytes.addAndGet(packet.payload().length);
    }

    /** Activates this sink only after the repository transition succeeded. */
    void activate() {
        if (!status.compareAndSet(RecordingStatus.INITIALIZING, RecordingStatus.RECORDING)) {
            throw new IllegalStateException("recording session cannot be activated from " + status.get());
        }
        recordingStartNanos.compareAndSet(0L, System.nanoTime());
    }

    /**
     * Marks the first initialization/capture failure. FINALIZING and terminal
     * states are never reset by a late callback.
     */
    boolean markFailed(ReplayFailureCode code, String description) {
        Objects.requireNonNull(code, "code");
        String safeDescription = requireDescription(description);
        for (;;) {
            RecordingStatus current = status.get();
            if (current == RecordingStatus.FAILED || current == RecordingStatus.AVAILABLE
                    || current == RecordingStatus.DELETING || current == RecordingStatus.FINALIZING) {
                return false;
            }
            if (status.compareAndSet(current, RecordingStatus.FAILED)) {
                failureCode.compareAndSet(null, code);
                failureDescription.compareAndSet(null, safeDescription);
                queue.closeForEnqueue();
                coordinator.unregister(id);
                return true;
            }
        }
    }

    /** Internal state used by Task 20/21 to access the prepared checkpoint. */
    ReplayCheckpoint initialCheckpoint() {
        return initialCheckpoint;
    }

    /** Internal request snapshot for the later writer/finalizer. */
    RecordingRequest request() {
        return request;
    }

    /** Internal queue hand-off for the Task-20 writer. */
    SessionPacketQueue queue() {
        return queue;
    }

    /** Internal scope snapshot for diagnostics and the later writer. */
    ResolvedRecordingScope resolvedScope() {
        return resolvedScope;
    }

    /** Records one completed segment without exposing mutable counters publicly. */
    void recordSegment() {
        segmentCount.incrementAndGet();
    }

    /** Records one additional checkpoint produced by the later checkpoint worker. */
    void recordCheckpoint() {
        checkpointCount.incrementAndGet();
    }

    /**
     * Completes the finalizer-owned lifecycle edge. Task 19 never calls this;
     * it is the narrow hand-off used by Task 21 after manifest publication.
     */
    boolean markAvailable() {
        return status.compareAndSet(RecordingStatus.FINALIZING, RecordingStatus.AVAILABLE);
    }

    /**
     * Records a finalizer failure without allowing a late callback to reset an
     * already terminal session.
     */
    boolean markFinalizerFailed(ReplayFailureCode code, String description) {
        Objects.requireNonNull(code, "code");
        String safeDescription = requireDescription(description);
        if (!status.compareAndSet(RecordingStatus.FINALIZING, RecordingStatus.FAILED)) {
            return false;
        }
        failureCode.compareAndSet(null, code);
        failureDescription.compareAndSet(null, safeDescription);
        queue.closeForEnqueue();
        coordinator.unregister(id);
        return true;
    }

    private static long elapsedSince(long startNanos) {
        try {
            return Math.max(0L, Math.subtractExact(System.nanoTime(), startNanos));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String requireDescription(String description) {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            return "recording session failed";
        }
        return description.length() > 512 ? description.substring(0, 512) : description;
    }
}
