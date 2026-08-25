package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.CheckpointSignalSource;
import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.ReplayCheckpointWriter;
import dev.voldechse.replayframework.format.ReplaySegmentWriter;
import dev.voldechse.replayframework.format.SeekPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final CheckpointSignalSource checkpointSignals;
    private final Consumer<ReplayCompletionReason> budgetStop;
    private final BiConsumer<ReplayFailureCode, Throwable> failure;
    private final Consumer<RecordingArtifacts> completion;
    private final Queue<DimensionSignal> dimensionSignals = new ConcurrentLinkedQueue<>();
    private final CheckpointSignalSource.CheckpointSignalListener signalListener =
            (captureTimeNanos, serverTick) -> {
                if (serverTick < 0L) {
                    return;
                }
                dimensionSignals.add(new DimensionSignal(captureTimeNanos, serverTick));
            };
    private final AtomicBoolean loopStarted = new AtomicBoolean();
    private final AtomicBoolean signalInstalled = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicBoolean completionReported = new AtomicBoolean();
    private final List<Path> segmentFiles = new ArrayList<>();
    private final List<Path> checkpointFiles = new ArrayList<>();
    private final List<SeekPoint> seekPoints = new ArrayList<>();

    private State state = State.NEW;
    private ReplaySegmentWriter segmentWriter;
    private Path segmentTarget;
    private int segmentOrdinal = -1;
    private int lastSealedSegmentOrdinal = -1;
    private long lastSealedSegmentLength;
    private long segmentStartElapsedNanos;
    private long lastElapsedNanos = -1L;
    private long lastServerTick = -1L;
    private int lastSequence = -1;
    private int activeCheckpointOrdinal;
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
            CheckpointSignalSource checkpointSignals,
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
        this.checkpointSignals = Objects.requireNonNull(checkpointSignals, "checkpointSignals");
        this.budgetStop = Objects.requireNonNull(budgetStop, "budgetStop");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    RecordingSegmentAppender(
            Path workspace,
            String adapterId,
            long recordingStartCaptureTimeNanos,
            SessionPacketQueue queue,
            ReplayCheckpoint initialCheckpoint,
            ReplayBudget budget,
            CheckpointScheduler checkpointScheduler,
            CheckpointSignalSource checkpointSignals,
            Consumer<ReplayCompletionReason> budgetStop,
            BiConsumer<ReplayFailureCode, Throwable> failure) {
        this(
                workspace,
                adapterId,
                recordingStartCaptureTimeNanos,
                queue,
                initialCheckpoint,
                budget,
                checkpointScheduler,
                checkpointSignals,
                budgetStop,
                failure,
                artifacts -> {
                });
    }

    /** Starts this appender on exactly one virtual thread. */
    Thread startWriter() {
        if (!loopStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("recording appender was already started");
        }
        installSignalListener();
        return Thread.startVirtualThread(this::runWriterLoopInternal);
    }

    /** Drains and writes the session queue on the calling writer thread. */
    void runWriterLoop() {
        if (!loopStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("recording appender was already started");
        }
        installSignalListener();
        runWriterLoopInternal();
    }

    private void runWriterLoopInternal() {
        state = State.RUNNING;
        try {
            initializeArtifacts();
            while (true) {
                processDimensionSignals();
                Optional<CapturedPacket> next = queue.awaitNext();
                if (next.isEmpty()) {
                    break;
                }
                processPacket(next.orElseThrow());
            }
            processDimensionSignals();
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
        Path segments = safeDirectory("segments");
        Path checkpoints = safeDirectory("checkpoints");
        Files.createDirectories(segments);
        Files.createDirectories(checkpoints);

        checkpointScheduler.registerInitial(initialCheckpoint);
        Path initialPath = safeArtifact("checkpoints", 0, "checkpoint");
        writeCheckpoint(initialPath, initialCheckpoint);
        checkpointFiles.add(initialPath);
        activeCheckpointOrdinal = 0;
        seekPoints.add(new SeekPoint(0L, 0, 0, 0L));
        for (RawPacketFrame frame : initialCheckpoint.initializationFrames()) {
            RecordingBudgetTracker.BudgetObservation observation = budgetTracker.afterFrame(
                    frame, RecordingBudgetTracker.UnitKind.CHECKPOINT_FRAME);
            notifyBudgetStop(observation.completionReason());
        }
    }

    private void processPacket(CapturedPacket captured) throws IOException {
        RawPacketFrame frame = toFrame(captured);
        validateOrder(frame);
        openSegmentIfNeeded(frame.elapsedNanos());
        rotateForDurationIfNeeded(frame.elapsedNanos());
        if (segmentWriter == null) {
            openSegmentIfNeeded(frame.elapsedNanos());
        }

        long frameOffset = segmentWriter.uncompressedLength();
        try {
            segmentWriter.append(frame);
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        seekPoints.add(new SeekPoint(
                frame.elapsedNanos(), activeCheckpointOrdinal, segmentOrdinal, frameOffset));
        lastElapsedNanos = frame.elapsedNanos();
        lastServerTick = frame.serverTick();
        lastSequence = frame.sequence();
        RecordingBudgetTracker.BudgetObservation observation = budgetTracker.afterFrame(
                frame, RecordingBudgetTracker.UnitKind.DELTA_FRAME);
        notifyBudgetStop(observation.completionReason());

        if (segmentWriter.uncompressedLength() >= budget.maxSegmentBytes()
                && !budgetTracker.stopRequested()) {
            finishOpenSegment();
        }

        Optional<CheckpointEncoder.CheckpointRequest> periodic =
                checkpointScheduler.dueForElapsed(frame.elapsedNanos(), frame.serverTick());
        periodic.ifPresent(this::writeCheckpointRequestUnchecked);
        processDimensionSignals();
    }

    private void openSegmentIfNeeded(long elapsedNanos) throws IOException {
        if (segmentWriter != null) {
            return;
        }
        // A structural rotation may have sealed the previous segment before a
        // budget callback became visible. Already accepted queue entries still
        // need a complete artifact, even if that creates the documented
        // frame-accurate overhang beyond the configured session budget.
        int nextOrdinal = segmentOrdinal + 1;
        Path target = safeArtifact("segments", nextOrdinal, "segment");
        segmentWriter = new ReplaySegmentWriter(target, adapterId);
        segmentTarget = target;
        segmentOrdinal = nextOrdinal;
        segmentStartElapsedNanos = elapsedNanos;
        RecordingBudgetTracker.BudgetObservation observation =
                budgetTracker.afterSegmentOpened(segmentOrdinal + 1L);
        notifyBudgetStop(observation.completionReason());
    }

    private void rotateForDurationIfNeeded(long elapsedNanos) throws IOException {
        if (segmentWriter == null || segmentWriter.frameCount() == 0L) {
            return;
        }
        Optional<java.time.Duration> limit = budget.maxSegmentDuration();
        if (limit.isEmpty()) {
            return;
        }
        long segmentElapsed;
        try {
            segmentElapsed = Math.subtractExact(elapsedNanos, segmentStartElapsedNanos);
        } catch (ArithmeticException overflow) {
            throw new CorruptWriterException(
                    new IllegalArgumentException("segment duration calculation overflow", overflow));
        }
        long limitNanos;
        try {
            limitNanos = limit.orElseThrow().toNanos();
        } catch (ArithmeticException overflow) {
            limitNanos = Long.MAX_VALUE;
        }
        if (segmentElapsed < limitNanos) {
            return;
        }
        if (budgetTracker.stopRequested()) {
            return;
        }
        finishOpenSegment();
    }

    private void processDimensionSignals() throws IOException {
        DimensionSignal signal;
        while ((signal = dimensionSignals.poll()) != null) {
            long elapsedNanos;
            try {
                elapsedNanos = Math.subtractExact(
                        signal.captureTimeNanos(), recordingStartCaptureTimeNanos);
            } catch (ArithmeticException overflow) {
                throw new CorruptWriterException(
                        new IllegalArgumentException("dimension signal time cannot be rebased", overflow));
            }
            if (elapsedNanos < 0L) {
                throw new IllegalArgumentException("dimension signal precedes recording start");
            }
            checkpointScheduler.dueForDimensionChange(elapsedNanos, signal.serverTick())
                    .ifPresent(this::writeCheckpointRequestUnchecked);
        }
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
        ReplayCheckpoint encoded;
        try {
            encoded = checkpointScheduler.encode(request).toCompletableFuture().join();
        } catch (CompletionException exception) {
            throw new AdapterCheckpointException(unwrap(exception));
        } catch (RuntimeException exception) {
            throw new AdapterCheckpointException(exception);
        }
        if (encoded == null || encoded.elapsedNanos() != request.elapsedNanos()) {
            throw new CorruptWriterException(
                    new IllegalArgumentException("checkpoint encoder returned an invalid time"));
        }
        for (RawPacketFrame frame : encoded.initializationFrames()) {
            if (frame.elapsedNanos() != request.elapsedNanos()
                    || frame.serverTick() != request.serverTick()) {
                throw new CorruptWriterException(
                        new IllegalArgumentException(
                                "checkpoint frame does not match its request point"));
            }
        }
        int ordinal = checkpointScheduler.nextOrdinal();
        ReplayCheckpoint canonical;
        try {
            canonical = new ReplayCheckpoint(
                    ordinal, request.elapsedNanos(), encoded.initializationFrames());
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        Path target = safeArtifact("checkpoints", ordinal, "checkpoint");
        writeCheckpoint(target, canonical);
        checkpointFiles.add(target);
        for (RawPacketFrame frame : canonical.initializationFrames()) {
            RecordingBudgetTracker.BudgetObservation observation = budgetTracker.afterFrame(
                    frame, RecordingBudgetTracker.UnitKind.CHECKPOINT_FRAME);
            notifyBudgetStop(observation.completionReason());
        }
        long offset = segmentWriter == null
                ? lastSealedSegmentLength
                : segmentWriter.uncompressedLength();
        int seekSegment = segmentWriter == null
                ? Math.max(0, lastSealedSegmentOrdinal)
                : segmentOrdinal;
        seekPoints.add(new SeekPoint(
                canonical.elapsedNanos(), canonical.ordinal(), seekSegment, offset));
        try {
            checkpointScheduler.markWritten(canonical);
        } catch (IllegalArgumentException invariantFailure) {
            throw new CorruptWriterException(invariantFailure);
        }
        activeCheckpointOrdinal = canonical.ordinal();
    }

    private void writeCheckpoint(Path target, ReplayCheckpoint checkpoint) throws IOException {
        try {
            new ReplayCheckpointWriter(target, adapterId).write(checkpoint);
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
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
                captured.payload());
    }

    private void validateOrder(RawPacketFrame frame) {
        if (lastElapsedNanos < 0L) {
            return;
        }
        boolean beforeLast = frame.elapsedNanos() < lastElapsedNanos
                || (frame.serverTick() < lastServerTick)
                || (frame.serverTick() == lastServerTick && frame.sequence() < lastSequence);
        if (beforeLast) {
            throw new IllegalArgumentException("captured frames moved backwards");
        }
    }

    private void finishOpenSegment() throws IOException {
        if (segmentWriter == null) {
            return;
        }
        long sealedLength = segmentWriter.uncompressedLength();
        try {
            segmentWriter.finish();
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        segmentFiles.add(Objects.requireNonNull(segmentTarget, "segmentTarget"));
        lastSealedSegmentOrdinal = segmentOrdinal;
        lastSealedSegmentLength = sealedLength;
        segmentWriter = null;
        segmentTarget = null;
    }

    private void seal() throws IOException {
        if (state != State.RUNNING) {
            return;
        }
        finishOpenSegment();
        checkpointSignals.uninstall(signalListener);
        signalInstalled.set(false);
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
                List.copyOf(segmentFiles),
                List.copyOf(checkpointFiles),
                List.copyOf(seekPoints),
                snapshot.lastElapsedNanos(),
                snapshot.payloadBytes(),
                snapshot.packetCount(),
                segmentFiles.size(),
                checkpointFiles.size(),
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
        try {
            checkpointSignals.uninstall(signalListener);
            signalInstalled.set(false);
        } catch (Throwable ignored) {
            // The original writer failure remains authoritative.
        }
        if (segmentWriter != null) {
            try {
                segmentWriter.abort();
            } catch (IOException cleanupFailure) {
                cause.addSuppressed(cleanupFailure);
            }
            segmentWriter = null;
            segmentTarget = null;
        }
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

    private Path safeArtifact(String directory, int ordinal, String suffix) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("artifact ordinal must not be negative");
        }
        Path relative = Path.of(directory, "%08d.%s".formatted(ordinal, suffix));
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace) || target.isAbsolute() && !target.startsWith(workspace)) {
            throw new SecurityException("artifact path escapes the recording workspace");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("artifact target already exists: " + relative);
        }
        return target;
    }

    private Path safeDirectory(String name) {
        Path directory = workspace.resolve(name).normalize();
        if (!directory.startsWith(workspace)) {
            throw new SecurityException("artifact directory escapes the recording workspace");
        }
        if (Files.isSymbolicLink(directory)) {
            throw new SecurityException("artifact directory must not be a symbolic link");
        }
        return directory;
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

    private void installSignalListener() {
        if (signalInstalled.compareAndSet(false, true)) {
            try {
                checkpointSignals.install(signalListener);
            } catch (Throwable failure) {
                signalInstalled.set(false);
                throw failure;
            }
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

    private record DimensionSignal(long captureTimeNanos, long serverTick) {
    }

    record RecordingArtifacts(
            List<Path> segmentFiles,
            List<Path> checkpointFiles,
            List<SeekPoint> seekPoints,
            long durationNanos,
            long payloadBytes,
            long packetCount,
            long segmentCount,
            long checkpointCount,
            Optional<ReplayCompletionReason> completionReason) {
        RecordingArtifacts {
            segmentFiles = List.copyOf(Objects.requireNonNull(segmentFiles, "segmentFiles"));
            checkpointFiles = List.copyOf(
                    Objects.requireNonNull(checkpointFiles, "checkpointFiles"));
            seekPoints = List.copyOf(Objects.requireNonNull(seekPoints, "seekPoints"));
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
