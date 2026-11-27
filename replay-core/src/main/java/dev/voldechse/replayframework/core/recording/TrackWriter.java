package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.ReplayCheckpointWriter;
import dev.voldechse.replayframework.format.ReplaySegmentWriter;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.format.TrackDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the artifacts of one track of a recording: its segments, its
 * checkpoints and its seek points.
 *
 * <p>Every track has a checkpoint for every barrier of the recording, so the
 * checkpoint ordinals of all tracks are the same and every index starts at
 * {@code (0, 0, 0, 0)}. A track that starts later writes empty checkpoints
 * for the barriers before it. Owned by the writer thread of the recording.</p>
 */
final class TrackWriter {

    private final TrackDescriptor descriptor;
    private final Path directory;
    private final String adapterId;
    private final ReplayBudget budget;
    private final List<Path> segmentFiles = new ArrayList<>();
    private final List<Path> checkpointFiles = new ArrayList<>();
    private final List<SeekPoint> seekPoints = new ArrayList<>();

    private ReplaySegmentWriter segmentWriter;
    private Path segmentTarget;
    private int segmentOrdinal = -1;
    private int lastSealedSegmentOrdinal = -1;
    private long lastSealedSegmentLength;
    private long segmentStartElapsedNanos;
    private int activeCheckpointOrdinal = -1;

    TrackWriter(TrackDescriptor descriptor, Path workspace, String adapterId, ReplayBudget budget)
            throws IOException {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.directory = safeDirectory(Objects.requireNonNull(workspace, "workspace"), descriptor.directory());
        Files.createDirectories(directory.resolve("segments"));
        Files.createDirectories(directory.resolve("checkpoints"));
    }

    TrackDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Writes the checkpoint of the next barrier and the seek point that starts
     * from it. Its ordinal must follow the previous one.
     */
    void writeCheckpoint(ReplayCheckpoint checkpoint) throws IOException {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (checkpoint.ordinal() != activeCheckpointOrdinal + 1) {
            throw new IllegalArgumentException("checkpoint ordinals of a track must be consecutive");
        }
        Path target = artifact("checkpoints", checkpoint.ordinal(), "checkpoint");
        new ReplayCheckpointWriter(target, adapterId, descriptor).write(checkpoint);
        checkpointFiles.add(target);
        long offset = segmentWriter == null ? lastSealedSegmentLength : segmentWriter.uncompressedLength();
        int seekSegment = segmentWriter == null ? Math.max(0, lastSealedSegmentOrdinal) : segmentOrdinal;
        seekPoints.add(new SeekPoint(checkpoint.elapsedNanos(), checkpoint.ordinal(), seekSegment, offset));
        activeCheckpointOrdinal = checkpoint.ordinal();
    }

    /** Whether the open segment is older than the configured segment duration. */
    boolean segmentDurationReached(long elapsedNanos) {
        if (segmentWriter == null || segmentWriter.frameCount() == 0L) {
            return false;
        }
        Optional<java.time.Duration> limit = budget.maxSegmentDuration();
        if (limit.isEmpty()) {
            return false;
        }
        long limitNanos;
        try {
            limitNanos = limit.get().toNanos();
        } catch (ArithmeticException overflow) {
            limitNanos = Long.MAX_VALUE;
        }
        return Math.subtractExact(elapsedNanos, segmentStartElapsedNanos) >= limitNanos;
    }

    /** Whether the open segment reached the configured segment size. */
    boolean segmentSizeReached() {
        return segmentWriter != null && segmentWriter.uncompressedLength() >= budget.maxSegmentBytes();
    }

    /**
     * Appends a frame, opening a segment when none is open.
     *
     * @return whether a new segment was opened for the frame
     */
    boolean append(RawPacketFrame frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        boolean opened = false;
        if (segmentWriter == null) {
            int nextOrdinal = segmentOrdinal + 1;
            Path target = artifact("segments", nextOrdinal, "segment");
            segmentWriter = new ReplaySegmentWriter(target, adapterId, descriptor);
            segmentTarget = target;
            segmentOrdinal = nextOrdinal;
            segmentStartElapsedNanos = frame.elapsedNanos();
            opened = true;
        }
        long frameOffset = segmentWriter.uncompressedLength();
        segmentWriter.append(frame);
        seekPoints.add(new SeekPoint(
                frame.elapsedNanos(), activeCheckpointOrdinal, segmentOrdinal, frameOffset));
        return opened;
    }

    /** Seals the open segment, if any. */
    void finishOpenSegment() throws IOException {
        if (segmentWriter == null) {
            return;
        }
        long sealedLength = segmentWriter.uncompressedLength();
        segmentWriter.finish();
        segmentFiles.add(segmentTarget);
        lastSealedSegmentOrdinal = segmentOrdinal;
        lastSealedSegmentLength = sealedLength;
        segmentWriter = null;
        segmentTarget = null;
    }

    /** Discards the open segment after a failure. */
    void abort(Throwable cause) {
        if (segmentWriter == null) {
            return;
        }
        try {
            segmentWriter.abort();
        } catch (IOException cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
        segmentWriter = null;
        segmentTarget = null;
    }

    /** The sealed artifacts of this track. */
    TrackArtifacts artifacts() {
        return new TrackArtifacts(descriptor, segmentFiles, checkpointFiles, seekPoints);
    }

    private Path artifact(String kind, int ordinal, String suffix) {
        Path target = directory.resolve(kind).resolve("%08d.%s".formatted(ordinal, suffix)).normalize();
        if (!target.startsWith(directory)) {
            throw new SecurityException("artifact path escapes the track directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("artifact target already exists: " + target.getFileName());
        }
        return target;
    }

    private static Path safeDirectory(Path workspace, String relative) {
        Path resolved = workspace.resolve(relative).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("track directory escapes the recording workspace");
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new SecurityException("track directory must not be a symbolic link");
        }
        return resolved;
    }

    /**
     * The sealed artifacts of one track.
     *
     * @param descriptor the track
     * @param segmentFiles segments in order
     * @param checkpointFiles checkpoints in ordinal order, one per barrier
     * @param seekPoints the track's index
     */
    record TrackArtifacts(
            TrackDescriptor descriptor,
            List<Path> segmentFiles,
            List<Path> checkpointFiles,
            List<SeekPoint> seekPoints) {
        TrackArtifacts {
            Objects.requireNonNull(descriptor, "descriptor");
            segmentFiles = List.copyOf(segmentFiles);
            checkpointFiles = List.copyOf(checkpointFiles);
            seekPoints = List.copyOf(seekPoints);
        }
    }
}
