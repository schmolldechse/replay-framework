package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.TrackKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Single-owner writer for the immutable artifacts of one recording session.
 *
 * <p>The loop must run on the session's virtual thread. No capture producer
 * may call a format writer or mutate the artifact lists.</p>
 */
final class RecordingSegmentAppender implements AutoCloseable {
    private final Path workspace;
    private final String adapterId;
    private final long recordingStartCaptureTimeNanos;
    private final SessionPacketQueue queue;
    private final ReplayCheckpoint initialCheckpoint;
    private final ReplayBudget budget;
    private final RecordingBudgetTracker budgetTracker;
    private final CheckpointScheduler checkpointScheduler;
    private final Consumer<ReplayCompletionReason> budgetStop;
    private final BiConsumer<ReplayFailureCode, Throwable> failure;
    private final Consumer<RecordingArtifacts> completion;
    private final AtomicBoolean loopStarted = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicBoolean completionReported = new AtomicBoolean();
    /** The tracks of the recording by key, in order of their IDs. */
    private final Map<TrackKey, TrackWriter> tracks = new LinkedHashMap<>();
    /** The replay time of every checkpoint barrier, by ordinal. */
    private final List<Long> checkpointTimes = new ArrayList<>();

    private State state = State.NEW;
    private long segmentCount;
    private long lastElapsedNanos = -1L;
    private long lastServerTick = -1L;
    private int lastSequence = -1;
    private int activeCheckpointOrdinal;
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
        this.adapterId = requireAdapterId(adapterId);
        this.recordingStartCaptureTimeNanos = recordingStartCaptureTimeNanos;
        this.queue = Objects.requireNonNull(queue, "queue");
        this.initialCheckpoint = Objects.requireNonNull(initialCheckpoint, "initialCheckpoint");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.budgetTracker = new RecordingBudgetTracker(budget);
        this.checkpointScheduler = Objects.requireNonNull(
                checkpointScheduler, "checkpointScheduler");
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
            fail(classify(unexpected), unwrap(unexpected));
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
        checkpointScheduler.registerInitial(initialCheckpoint);
        // The global track always exists, so every recording has track 0.
        track(TrackKeys.scene(Optional.empty()));
        writeBarrier(initialCheckpoint, List.of(), List.of());
        for (RawPacketFrame frame : initialCheckpoint.initializationFrames()) {
            RecordingBudgetTracker.BudgetObservation observation = budgetTracker.afterFrame(
                    frame, RecordingBudgetTracker.UnitKind.CHECKPOINT_FRAME);
            notifyBudgetStop(observation.completionReason());
        }
    }

    private void processPacket(CapturedPacket captured) throws IOException {
        appendPacket(captured);

        Optional<CheckpointEncoder.CheckpointRequest> periodic =
                checkpointScheduler.dueForElapsed(lastElapsedNanos, lastServerTick);
        periodic.ifPresent(this::writeCheckpointRequestUnchecked);
    }

    /** Appends one ordered delta without recursively scheduling a checkpoint. */
    private void appendPacket(CapturedPacket captured) throws IOException {
        RawPacketFrame frame = toFrame(captured);
        validateOrder(frame);
        TrackWriter track = track(TrackKeys.of(captured.context().route(), frame.world()));
        boolean rotate;
        try {
            rotate = track.segmentDurationReached(frame.elapsedNanos());
        } catch (ArithmeticException overflow) {
            throw new CorruptWriterException(
                    new IllegalArgumentException("segment duration calculation overflow", overflow));
        }
        if (rotate && !budgetTracker.stopRequested()) {
            finishSegment(track);
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
            finishSegment(track);
        }
    }

    /**
     * The track of a key, created on first use. A track created after
     * the first barrier gets empty checkpoints for the earlier barriers, so
     * all tracks share their checkpoint ordinals.
     */
    private TrackWriter track(TrackKeys.Track track) throws IOException {
        TrackWriter existing = tracks.get(track.key());
        if (existing != null) {
            return existing;
        }
        int id = tracks.size();
        TrackWriter created;
        try {
            created = new TrackWriter(track.key().describe(id, track.label()), workspace, adapterId, budget);
            for (int ordinal = 0; ordinal < checkpointTimes.size(); ordinal++) {
                created.writeCheckpoint(new ReplayCheckpoint(
                        ordinal, checkpointTimes.get(ordinal), List.of()));
            }
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        tracks.put(track.key(), created);
        return created;
    }

    /**
     * Writes one checkpoint barrier: each world track gets the scene frames
     * of its world, each state track the state of its subject, each block
     * state track the state of its world's blocks, and every other track an
     * empty checkpoint.
     */
    private void writeBarrier(
            ReplayCheckpoint checkpoint,
            List<CheckpointEncoder.SubjectState> subjects,
            List<CheckpointEncoder.BlockStates> blockStates)
            throws IOException {
        Map<TrackKey, List<RawPacketFrame>> byTrack = new LinkedHashMap<>();
        for (RawPacketFrame frame : checkpoint.initializationFrames()) {
            TrackKeys.Track scene = TrackKeys.scene(frame.world());
            if (!byTrack.containsKey(scene.key())) {
                track(scene);
            }
            byTrack.computeIfAbsent(scene.key(), ignored -> new ArrayList<>()).add(frame);
        }
        for (CheckpointEncoder.SubjectState state : subjects) {
            TrackKeys.Track subject = TrackKeys.subject(state.subject());
            track(subject);
            byTrack.put(subject.key(), state.frames());
        }
        for (CheckpointEncoder.BlockStates blocks : blockStates) {
            if (blocks.frames().isEmpty()) {
                continue;
            }
            TrackKeys.Track blockState = TrackKeys.blockState(blocks.world());
            track(blockState);
            byTrack.put(blockState.key(), blocks.frames());
        }
        try {
            for (Map.Entry<TrackKey, TrackWriter> entry : tracks.entrySet()) {
                entry.getValue().writeCheckpoint(new ReplayCheckpoint(
                        checkpoint.ordinal(),
                        checkpoint.elapsedNanos(),
                        byTrack.getOrDefault(entry.getKey(), List.of())));
            }
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        checkpointTimes.add(checkpoint.elapsedNanos());
        activeCheckpointOrdinal = checkpoint.ordinal();
    }

    private void writeCheckpointRequestUnchecked(CheckpointEncoder.CheckpointRequest request) {
        try {
            writeCheckpointRequest(request);
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        }
    }

    private void writeCheckpointRequest(CheckpointEncoder.CheckpointRequest request)
            throws IOException {
        CheckpointEncoder.CheckpointCapture capture;
        try {
            capture = checkpointScheduler.capture(request).toCompletableFuture().join();
        } catch (CompletionException exception) {
            throw new AdapterCheckpointException(unwrap(exception));
        } catch (RuntimeException exception) {
            throw new AdapterCheckpointException(exception);
        }
        if (capture == null) {
            throw new CorruptWriterException(
                    new IllegalArgumentException("checkpoint encoder returned null capture"));
        }
        long captureElapsedNanos;
        try {
            captureElapsedNanos = Math.subtractExact(
                    capture.point().captureTimeNanos(), recordingStartCaptureTimeNanos);
        } catch (ArithmeticException overflow) {
            throw new CorruptWriterException(
                    new IllegalArgumentException("checkpoint capture time cannot be rebased", overflow));
        }
        if (captureElapsedNanos < request.elapsedNanos()) {
            throw new CorruptWriterException(
                    new IllegalArgumentException("checkpoint capture precedes its request"));
        }
        drainPacketsThrough(capture.point());
        List<RawPacketFrame> barrierFrames = new ArrayList<>(capture.initializationFrames());
        capture.subjects().forEach(state -> barrierFrames.addAll(state.frames()));
        capture.blockStates().forEach(blocks -> barrierFrames.addAll(blocks.frames()));
        for (RawPacketFrame frame : barrierFrames) {
            if (frame.elapsedNanos() != captureElapsedNanos
                    || frame.serverTick() != capture.point().serverTick()) {
                throw new CorruptWriterException(
                        new IllegalArgumentException(
                                "checkpoint frame does not match its actual capture point"));
            }
        }
        int ordinal = checkpointScheduler.nextOrdinal();
        ReplayCheckpoint canonical;
        try {
            canonical = new ReplayCheckpoint(
                    ordinal, captureElapsedNanos, capture.initializationFrames());
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        writeBarrier(canonical, capture.subjects(), capture.blockStates());
        for (RawPacketFrame frame : canonical.initializationFrames()) {
            RecordingBudgetTracker.BudgetObservation observation = budgetTracker.afterFrame(
                    frame, RecordingBudgetTracker.UnitKind.CHECKPOINT_FRAME);
            notifyBudgetStop(observation.completionReason());
        }
        try {
            checkpointScheduler.markWritten(canonical, capture.point().serverTick());
        } catch (IllegalArgumentException invariantFailure) {
            throw new CorruptWriterException(invariantFailure);
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

    private static void finishSegment(TrackWriter track) {
        try {
            track.finishOpenSegment();
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
    }

    private void seal() throws IOException {
        if (state != State.RUNNING) {
            return;
        }
        tracks.values().forEach(RecordingSegmentAppender::finishSegment);
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
                tracks.values().stream().map(TrackWriter::artifacts).toList(),
                snapshot.lastElapsedNanos(),
                snapshot.payloadBytes(),
                snapshot.packetCount(),
                segmentCount,
                checkpointTimes.size(),
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
        tracks.values().forEach(track -> track.abort(cause));
        if (failureReported.compareAndSet(false, true)) {
            try {
                failure.accept(code, cause);
            } catch (Throwable ignored) {
                // A failure callback must never escape into the writer thread.
            }
        }
    }

    private static ReplayFailureCode classify(Throwable failure) {
        if (failure instanceof StorageWriteException || failure instanceof IOException) {
            return ReplayFailureCode.STORAGE_ERROR;
        }
        if (failure instanceof AdapterCheckpointException
                || failure instanceof IllegalArgumentException) {
            return ReplayFailureCode.ADAPTER_ERROR;
        }
        if (failure instanceof CorruptWriterException) {
            return ReplayFailureCode.CORRUPT_DATA;
        }
        return ReplayFailureCode.INTERNAL_ERROR;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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

    private static final class StorageWriteException extends RuntimeException {
        StorageWriteException(IOException cause) {
            super("replay artifact write failed", cause);
        }
    }

    private static final class CorruptWriterException extends RuntimeException {
        CorruptWriterException(Throwable cause) {
            super("replay artifact format invariant failed", cause);
        }
    }

    private static final class AdapterCheckpointException extends RuntimeException {
        AdapterCheckpointException(Throwable cause) {
            super("checkpoint encoder failed", cause);
        }
    }
}
