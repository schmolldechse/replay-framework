package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.core.recording.WriterFailures.AdapterCheckpointException;
import dev.voldechse.replayframework.core.recording.WriterFailures.CorruptWriterException;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * Writes the checkpoint barriers of a recording: the initial one, and each
 * one the scheduler asks for. A requested barrier is captured by the
 * adapter, checked against its capture point and written to every track
 * once the packets before that point are written.
 *
 * <p>Owned by the appender's writer thread.</p>
 */
final class CheckpointBarrierWriter {
    private final CheckpointScheduler scheduler;
    private final TrackSet tracks;
    private final long recordingStartCaptureTimeNanos;

    CheckpointBarrierWriter(
            CheckpointScheduler scheduler,
            TrackSet tracks,
            long recordingStartCaptureTimeNanos) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.tracks = Objects.requireNonNull(tracks, "tracks");
        this.recordingStartCaptureTimeNanos = recordingStartCaptureTimeNanos;
    }

    /** Writes the barrier of the checkpoint taken when the recording started. */
    void writeInitial(ReplayCheckpoint initial) throws IOException {
        scheduler.registerInitial(initial);
        tracks.writeBarrier(initial, List.of(), List.of());
    }

    /** The barrier due after the last written packet, if any. */
    Optional<CheckpointEncoder.CheckpointRequest> due(long lastElapsedNanos, long lastServerTick) {
        return scheduler.dueForElapsed(lastElapsedNanos, lastServerTick);
    }

    /**
     * Captures and writes a requested barrier.
     *
     * @param request the barrier the scheduler asked for
     * @param drain writes the packets captured before the barrier's capture point
     * @return the written checkpoint
     */
    ReplayCheckpoint write(CheckpointEncoder.CheckpointRequest request, PacketDrain drain)
            throws IOException {
        CheckpointEncoder.CheckpointCapture capture;
        try {
            capture = scheduler.capture(request).toCompletableFuture().join();
        } catch (CompletionException exception) {
            throw new AdapterCheckpointException(WriterFailures.unwrap(exception));
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
        drain.through(capture.point());
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
        int ordinal = scheduler.nextOrdinal();
        ReplayCheckpoint canonical;
        try {
            canonical = new ReplayCheckpoint(
                    ordinal, captureElapsedNanos, capture.initializationFrames());
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
        tracks.writeBarrier(canonical, capture.subjects(), capture.blockStates());
        try {
            scheduler.markWritten(canonical, capture.point().serverTick());
        } catch (IllegalArgumentException invariantFailure) {
            throw new CorruptWriterException(invariantFailure);
        }
        return canonical;
    }

    /** Writes the queued packets captured before a barrier's capture point. */
    @FunctionalInterface
    interface PacketDrain {
        void through(CheckpointEncoder.CapturePoint point) throws IOException;
    }
}
