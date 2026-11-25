package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.core.recording.WriterFailures.CorruptWriterException;
import dev.voldechse.replayframework.core.recording.WriterFailures.StorageWriteException;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Single-owner writer for the immutable artifacts of one recording session.
 * It drains the session queue, routes each packet to its track, rotates
 * segments, tracks the budget and seals the tracks; the tracks live in a
 * {@link TrackSet} and the checkpoint barriers are written by a
 * {@link CheckpointBarrierWriter}.
 *
 * <p>The loop must run on the session's virtual thread. No capture producer
 * may call a format writer or mutate the artifact lists.</p>
 */
final class RecordingSegmentAppender implements AutoCloseable {
    private final Path workspace;
    private final long recordingStartCaptureTimeNanos;
    private final SessionPacketQueue queue;
    private final ReplayCheckpoint initialCheckpoint;
    private final RecordingBudgetTracker budgetTracker;
    private final TrackSet tracks;
    private final CheckpointBarrierWriter barriers;
    private final Consumer<ReplayCompletionReason> budgetStop;
    private final BiConsumer<ReplayFailureCode, Throwable> failure;
    private final Consumer<RecordingArtifacts> completion;
    private final AtomicBoolean loopStarted = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicBoolean completionReported = new AtomicBoolean();

    private State state = State.NEW;
    private long segmentCount;
    private long lastElapsedNanos = -1L;
    private long lastServerTick = -1L;
    private int lastSequence = -1;
    private CapturedPacket deferredPacket;
    private boolean budgetStopNotified;
    private RecordingArtifacts sealedArtifacts;

    RecordingSegmentAppender(
            Path workspace,
            String adapterId,
            long recordingStartCaptureTimeNanos,
            SessionPacketQueue queue,
            ReplayCheckpoint initialCheckpoint,
            ReplayBudget budget,
            CheckpointScheduler checkpointScheduler,
            Consumer<ReplayCompletionReason> budgetStop,
            BiConsumer<ReplayFailureCode, Throwable> failure,
            Consumer<RecordingArtifacts> completion) {
        this.workspace = requireWorkspace(workspace);
        this.recordingStartCaptureTimeNanos = recordingStartCaptureTimeNanos;
        this.queue = Objects.requireNonNull(queue, "queue");
        this.initialCheckpoint = Objects.requireNonNull(initialCheckpoint, "initialCheckpoint");
        this.budgetTracker = new RecordingBudgetTracker(Objects.requireNonNull(budget, "budget"));
        this.tracks = new TrackSet(this.workspace, requireAdapterId(adapterId), budget);
        this.barriers = new CheckpointBarrierWriter(
                Objects.requireNonNull(checkpointScheduler, "checkpointScheduler"),
                tracks,
                recordingStartCaptureTimeNanos);
        this.budgetStop = Objects.requireNonNull(budgetStop, "budgetStop");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    /** Starts this appender on exactly one virtual thread. */
    Thread startWriter() {
        if (!loopStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("recording appender was already started");
        }
        return Thread.startVirtualThread(this::runWriterLoopInternal);
    }

    /** Drains and writes the session queue on the calling writer thread. */
    void runWriterLoop() {
        if (!loopStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("recording appender was already started");
        }
        runWriterLoopInternal();
    }

    private void runWriterLoopInternal() {
        state = State.RUNNING;
        try {
            initializeArtifacts();
            while (true) {
                Optional<CapturedPacket> next;
                if (deferredPacket != null) {
                    next = Optional.of(deferredPacket);
                    deferredPacket = null;
                } else {
                    next = queue.awaitNext();
                }
                if (next.isEmpty()) {
                    break;
                }
                processPacket(next.orElseThrow());
            }
            seal();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail(ReplayFailureCode.SERVER_CRASH, interrupted);
        } catch (Throwable unexpected) {
            fail(WriterFailures.classify(unexpected), WriterFailures.unwrap(unexpected));
        }
    }

    /** Returns immutable artifacts after {@link #runWriterLoop()} sealed them. */
    RecordingArtifacts finishForFinalization() {
        if (state != State.SEALED || sealedArtifacts == null) {
            throw new IllegalStateException("recording appender is not sealed: " + state);
        }
        return sealedArtifacts;
    }

    /** Aborts the open writer and reports one classified failure. */
    void abort(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        fail(ReplayFailureCode.INTERNAL_ERROR, cause);
    }

    @Override
    public void close() {
        if (state == State.NEW || state == State.RUNNING) {
            abort(new IllegalStateException("recording appender closed before sealing"));
        }
    }

    private void initializeArtifacts() throws IOException {
        // The global track always exists, so every recording has track 0.
        tracks.track(TrackKeys.scene(Optional.empty()));
        barriers.writeInitial(initialCheckpoint);
        countCheckpointFrames(initialCheckpoint);
    }

    private void processPacket(CapturedPacket captured) throws IOException {
        appendPacket(captured);

        Optional<CheckpointEncoder.CheckpointRequest> periodic =
                barriers.due(lastElapsedNanos, lastServerTick);
        periodic.ifPresent(this::writeCheckpointUnchecked);
    }

    /** Appends one ordered delta without recursively scheduling a checkpoint. */
    private void appendPacket(CapturedPacket captured) throws IOException {
        RawPacketFrame frame = toFrame(captured);
        validateOrder(frame);
        TrackWriter track = tracks.track(TrackKeys.of(captured.context().route(), frame.world()));
        boolean rotate;
        try {
            rotate = track.segmentDurationReached(frame.elapsedNanos());
        } catch (ArithmeticException overflow) {
            throw new CorruptWriterException(
                    new IllegalArgumentException("segment duration calculation overflow", overflow));
        }
        if (rotate && !budgetTracker.stopRequested()) {
            TrackSet.finishSegment(track);
        }

        // A structural rotation may have sealed the previous segment before a
        // budget callback became visible. Already accepted queue entries still
        // need a complete artifact, even if that creates the documented
        // frame-accurate overhang beyond the configured session budget.
        boolean opened;
        try {
            opened = track.append(frame);
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        if (opened) {
            segmentCount++;
            notifyBudgetStop(budgetTracker.afterSegmentOpened(segmentCount).completionReason());
        }
        lastElapsedNanos = frame.elapsedNanos();
        lastServerTick = frame.serverTick();
        lastSequence = frame.sequence();
        RecordingBudgetTracker.BudgetObservation observation = budgetTracker.afterFrame(
                frame, RecordingBudgetTracker.UnitKind.DELTA_FRAME);
        notifyBudgetStop(observation.completionReason());

        if (track.segmentSizeReached() && !budgetTracker.stopRequested()) {
            TrackSet.finishSegment(track);
        }
    }

    private void writeCheckpointUnchecked(CheckpointEncoder.CheckpointRequest request) {
        try {
            countCheckpointFrames(barriers.write(request, this::drainPacketsThrough));
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        }
    }

    private void countCheckpointFrames(ReplayCheckpoint checkpoint) {
        for (RawPacketFrame frame : checkpoint.initializationFrames()) {
            RecordingBudgetTracker.BudgetObservation observation = budgetTracker.afterFrame(
                    frame, RecordingBudgetTracker.UnitKind.CHECKPOINT_FRAME);
            notifyBudgetStop(observation.completionReason());
        }
    }

    /**
     * Advances the delta writer to the exclusive adapter capture barrier.
     * The Paper adapters publish all pre-barrier packets before allocating the
     * barrier, so no wait or reordering is required here.
     */
    private void drainPacketsThrough(CheckpointEncoder.CapturePoint point) throws IOException {
        while (deferredPacket == null) {
            Optional<CapturedPacket> next = queue.pollNext();
            if (next.isEmpty()) {
                return;
            }
            CapturedPacket packet = next.orElseThrow();
            if (isAfter(packet, point)) {
                deferredPacket = packet;
                return;
            }
            appendPacket(packet);
        }
    }

    private static boolean isAfter(
            CapturedPacket packet,
            CheckpointEncoder.CapturePoint point) {
        return packet.captureTimeNanos() > point.captureTimeNanos()
                || (packet.captureTimeNanos() == point.captureTimeNanos()
                && packet.serverTick() > point.serverTick())
                || (packet.captureTimeNanos() == point.captureTimeNanos()
                && packet.serverTick() == point.serverTick()
                && packet.sequence() > point.sequence());
    }

    private RawPacketFrame toFrame(CapturedPacket captured) {
        long elapsedNanos;
        try {
            elapsedNanos = Math.subtractExact(
                    captured.captureTimeNanos(), recordingStartCaptureTimeNanos);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("capture time cannot be rebased", overflow);
        }
        if (elapsedNanos < 0L) {
            throw new IllegalArgumentException("capture time precedes recording start");
        }
        return new RawPacketFrame(
                elapsedNanos,
                captured.serverTick(),
                captured.sequence(),
                captured.phase(),
                captured.packetId(),
                captured.payload(),
                TrackKeys.frameWorld(captured.context()));
    }

    private void validateOrder(RawPacketFrame frame) {
        if (lastElapsedNanos < 0L) {
            return;
        }
        boolean beforeLast = frame.elapsedNanos() < lastElapsedNanos
                || (frame.elapsedNanos() == lastElapsedNanos
                && frame.serverTick() < lastServerTick)
                || (frame.elapsedNanos() == lastElapsedNanos
                && frame.serverTick() == lastServerTick
                && frame.sequence() < lastSequence);
        if (beforeLast) {
            throw new IllegalArgumentException("captured frames moved backwards");
        }
    }

    private void seal() throws IOException {
        if (state != State.RUNNING) {
            return;
        }
        tracks.finishAll();
        state = State.SEALED;
        sealedArtifacts = snapshotArtifacts(budgetTracker.completionReason());
        if (completionReported.compareAndSet(false, true)) {
            try {
                completion.accept(sealedArtifacts);
            } catch (Throwable ignored) {
                // Completion is a notification after the artifact snapshot;
                // it cannot reopen or invalidate already sealed files.
            }
        }
    }

    private RecordingArtifacts snapshotArtifacts(Optional<ReplayCompletionReason> reason) {
        RecordingBudgetTracker.BudgetSnapshot snapshot = budgetTracker.snapshot();
        return new RecordingArtifacts(
                workspace,
                tracks.artifacts(),
                snapshot.lastElapsedNanos(),
                snapshot.payloadBytes(),
                snapshot.packetCount(),
                segmentCount,
                tracks.barrierCount(),
                reason);
    }

    private void notifyBudgetStop(Optional<ReplayCompletionReason> reason) {
        if (reason.isEmpty() || budgetStopNotified) {
            return;
        }
        budgetStopNotified = true;
        try {
            budgetStop.accept(reason.orElseThrow());
        } catch (Throwable callbackFailure) {
            throw new IllegalStateException("budget stop callback failed", callbackFailure);
        }
    }

    private void fail(ReplayFailureCode code, Throwable cause) {
        if (state == State.SEALED || state == State.FAILED) {
            return;
        }
        state = State.FAILED;
        tracks.abortAll(cause);
        if (failureReported.compareAndSet(false, true)) {
            try {
                failure.accept(code, cause);
            } catch (Throwable ignored) {
                // A failure callback must never escape into the writer thread.
            }
        }
    }

    private static Path requireWorkspace(Path workspace) {
        Objects.requireNonNull(workspace, "workspace");
        Path normalized = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace must be a directory");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new SecurityException("workspace must not be a symbolic link");
        }
        try {
            return normalized.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("workspace cannot be canonicalized", failure);
        }
    }

    private static String requireAdapterId(String adapterId) {
        Objects.requireNonNull(adapterId, "adapterId");
        if (adapterId.isBlank() || adapterId.contains("/") || adapterId.contains("\\")) {
            throw new IllegalArgumentException("adapterId is not a stable path-independent value");
        }
        return adapterId;
    }

    private enum State {
        /** Constructor state; no writer thread owns the session yet. */
        NEW,
        /** The virtual writer owns all artifact mutations and drains the queue. */
        RUNNING,
        /** Local files are sealed; this is not yet an AVAILABLE publication. */
        SEALED,
        /** A technical failure prevents a clean finalization handoff. */
        FAILED
    }

    /**
     * The sealed artifacts of a recording.
     *
     * @param workspace the recording workspace; every track's files are below it
     * @param tracks the tracks in order of their IDs
     * @param durationNanos replay duration
     * @param payloadBytes recorded payload bytes
     * @param packetCount recorded packets
     * @param segmentCount segments over all tracks
     * @param checkpointCount checkpoint barriers; every track has one checkpoint per barrier
     * @param completionReason why the recording ended, when a budget ended it
     */
    record RecordingArtifacts(
            Path workspace,
            List<TrackWriter.TrackArtifacts> tracks,
            long durationNanos,
            long payloadBytes,
            long packetCount,
            long segmentCount,
            long checkpointCount,
            Optional<ReplayCompletionReason> completionReason) {
        RecordingArtifacts {
            Objects.requireNonNull(workspace, "workspace");
            tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
            completionReason = Objects.requireNonNull(completionReason, "completionReason");
            if (durationNanos < 0L || payloadBytes < 0L || packetCount < 0L
                    || segmentCount < 0L || checkpointCount < 0L) {
                throw new IllegalArgumentException("recording artifact counters must not be negative");
            }
        }
    }
}
