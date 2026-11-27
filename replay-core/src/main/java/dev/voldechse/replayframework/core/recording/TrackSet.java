package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.api.recording.ReplayBudget;
import dev.voldechse.replayframework.core.recording.WriterFailures.CorruptWriterException;
import dev.voldechse.replayframework.core.recording.WriterFailures.StorageWriteException;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import dev.voldechse.replayframework.format.TrackKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The tracks of one recording by key, in order of their IDs, and the
 * checkpoint barriers they share. Every track has one checkpoint per
 * barrier, including the barriers written before it was created.
 *
 * <p>Owned by the appender's writer thread.</p>
 */
final class TrackSet {
    private final Path workspace;
    private final String adapterId;
    private final ReplayBudget budget;
    private final Map<TrackKey, TrackWriter> tracks = new LinkedHashMap<>();
    /** The replay time of every checkpoint barrier, by ordinal. */
    private final List<Long> barrierTimes = new ArrayList<>();

    TrackSet(Path workspace, String adapterId, ReplayBudget budget) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /**
     * The track of a key, created on first use. A track created after
     * the first barrier gets empty checkpoints for the earlier barriers, so
     * all tracks share their checkpoint ordinals.
     */
    TrackWriter track(TrackKeys.Track track) throws IOException {
        TrackWriter existing = tracks.get(track.key());
        if (existing != null) {
            return existing;
        }
        int id = tracks.size();
        TrackWriter created;
        try {
            created = new TrackWriter(track.key().describe(id, track.label()), workspace, adapterId, budget);
            for (int ordinal = 0; ordinal < barrierTimes.size(); ordinal++) {
                created.writeCheckpoint(new ReplayCheckpoint(
                        ordinal, barrierTimes.get(ordinal), List.of()));
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
    void writeBarrier(
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
        barrierTimes.add(checkpoint.elapsedNanos());
    }

    /** The checkpoint barriers written so far. */
    int barrierCount() {
        return barrierTimes.size();
    }

    /** Finishes the open segment of every track. */
    void finishAll() {
        tracks.values().forEach(TrackSet::finishSegment);
    }

    /** Aborts every track after a failure. */
    void abortAll(Throwable cause) {
        tracks.values().forEach(track -> track.abort(cause));
    }

    /** The sealed artifacts of every track, in order of their IDs. */
    List<TrackWriter.TrackArtifacts> artifacts() {
        return tracks.values().stream().map(TrackWriter::artifacts).toList();
    }

    /** Finishes the open segment of a track. */
    static void finishSegment(TrackWriter track) {
        try {
            track.finishOpenSegment();
        } catch (IOException exception) {
            throw new StorageWriteException(exception);
        } catch (IllegalArgumentException formatFailure) {
            throw new CorruptWriterException(formatFailure);
        }
    }
}
