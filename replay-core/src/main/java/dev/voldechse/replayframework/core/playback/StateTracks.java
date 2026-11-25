package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.playback.StateDecoder;

import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.format.TrackDescriptor;
import dev.voldechse.replayframework.format.TrackKey;
import dev.voldechse.replayframework.format.TrackKind;
import dev.voldechse.replayframework.format.TrackSubject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The state tracks of one playback, opened when a subject's or a block's
 * state is first asked for and closed with the playback.
 *
 * <p>A state is the checkpoint of the subject's track at the barrier the
 * playback position lies after, followed by the track's frames up to the
 * position. The bridge turns these frames into the version-neutral state.
 * A block's state is read the same way from the block state track of its
 * world, which holds the blocks of that world.</p>
 *
 * <p>Integrations ask for a state often, for example to follow it in a menu.
 * Each track therefore keeps the checkpoint of its last barrier and its last
 * decoded state; a query whose barrier and frames are unchanged returns that
 * state without reading the checkpoint or decoding again.</p>
 */
final class StateTracks implements AutoCloseable {

    /** The tracks with the state role, by key. */
    private final Map<TrackKey, TrackDescriptor> tracks;
    private final ReplayIndex reference;
    private final long durationNanos;
    private final StateDecoder bridge;
    private final Function<TrackDescriptor, CompletionStage<MergedTrackData.Track>> opener;
    private final Map<Integer, CompletableFuture<MergedTrackData.Track>> open = new ConcurrentHashMap<>();
    private final Map<Integer, CachedCheckpoint> checkpoints = new ConcurrentHashMap<>();
    private final Map<Integer, CachedState> states = new ConcurrentHashMap<>();
    private final Map<Integer, CachedBlocks> blocks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param tracks the replay's tracks; only those with the state role are used
     * @param reference the index the playback seeks on; its checkpoint
     *                  ordinals are those of every track
     * @param durationNanos the replay duration
     * @param bridge decodes state frames
     * @param opener opens a track's playback data
     */
    StateTracks(
            List<TrackDescriptor> tracks,
            ReplayIndex reference,
            long durationNanos,
            StateDecoder bridge,
            Function<TrackDescriptor, CompletionStage<MergedTrackData.Track>> opener) {
        Map<TrackKey, TrackDescriptor> byKey = new LinkedHashMap<>();
        for (TrackDescriptor track : tracks) {
            if (track.kind().role() == TrackKind.Role.STATE) {
                byKey.put(track.key(), track);
            }
        }
        this.tracks = byKey;
        this.reference = Objects.requireNonNull(reference, "reference");
        this.durationNanos = durationNanos;
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** No state tracks; for playbacks built without them. */
    static StateTracks none(StateDecoder bridge) {
        return new StateTracks(List.of(), ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L))), 0L,
                bridge, track -> CompletableFuture.failedFuture(new IllegalStateException("no tracks")));
    }

    /** The state of a subject at a playback position. */
    CompletionStage<Optional<SubjectState>> state(RecordedSubject subject, Duration position) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(position, "position");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("playback is closed"));
        }
        TrackSubject wanted = new TrackSubject(
                subject.kind() == RecordedSubject.Kind.PLAYER
                        ? TrackSubject.Kind.PLAYER
                        : TrackSubject.Kind.ENTITY,
                subject.id());
        Optional<TrackDescriptor> track = Optional.ofNullable(tracks.get(TrackKey.state(wanted)));
        if (track.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        int trackId = track.get().id();
        return read(track.get(), position).thenApply(read ->
                decode(trackId, read.ordinal(), subject, read.checkpoint(), read.after()));
    }

    /**
     * The state of a block at a playback position, such as the contents of a
     * chest. Empty when the recording has no such block there.
     */
    CompletionStage<Optional<SubjectState>> block(String world, BlockPosition block, Duration position) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(position, "position");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("playback is closed"));
        }
        Optional<TrackDescriptor> track = Optional.ofNullable(tracks.get(TrackKey.blockState(world)));
        if (track.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        int trackId = track.get().id();
        return read(track.get(), position).thenApply(read -> {
            CachedBlocks cached = blocks.compute(trackId, (id, previous) ->
                    previous != null && previous.ordinal() == read.ordinal() && previous.frames() == read.after().size()
                            ? previous
                            : new CachedBlocks(read.ordinal(), read.after().size(), new ConcurrentHashMap<>()));
            return cached.states().computeIfAbsent(block, wanted -> bridge.decodeBlock(wanted, read.all()));
        });
    }

    /** The checkpoint of a track at the barrier before a position and its frames up to the position. */
    private CompletionStage<Read> read(TrackDescriptor track, Duration position) {
        SeekPoint barrier = reference.seekCheckpointFloor(position)
                .orElseThrow(() -> new IllegalStateException("replay has no checkpoint before " + position));
        long end = Math.min(position.toNanos() + 1L, durationNanos + 1L);
        int ordinal = barrier.checkpointOrdinal();
        return open(track).thenCompose(data -> {
            SeekPoint anchor = data.anchor(barrier);
            CompletionStage<List<RawPacketFrame>> checkpoint = checkpoint(track.id(), ordinal, data, anchor);
            CompletionStage<List<RawPacketFrame>> deltas = data.data().framesAfter(anchor, Duration.ofNanos(end));
            return checkpoint.thenCombine(deltas, (base, after) -> new Read(ordinal, base, after));
        });
    }

    /** A track read up to a position. */
    private record Read(int ordinal, List<RawPacketFrame> checkpoint, List<RawPacketFrame> after) {
        List<RawPacketFrame> all() {
            List<RawPacketFrame> frames = new ArrayList<>(checkpoint.size() + after.size());
            frames.addAll(checkpoint);
            frames.addAll(after);
            return frames;
        }
    }

    /** The checkpoint frames of a track at a barrier, read once per barrier. */
    private CompletionStage<List<RawPacketFrame>> checkpoint(
            int trackId, int ordinal, MergedTrackData.Track data, SeekPoint anchor) {
        CachedCheckpoint cached = checkpoints.get(trackId);
        if (cached != null && cached.ordinal() == ordinal && !cached.frames().isCompletedExceptionally()) {
            return cached.frames();
        }
        CompletableFuture<List<RawPacketFrame>> frames =
                data.data().checkpointFrames(anchor).toCompletableFuture();
        checkpoints.put(trackId, new CachedCheckpoint(ordinal, frames));
        return frames;
    }

    /**
     * Decodes the state, unless the frames since the barrier are the same as
     * at the last query. Frames after a barrier only grow with the position,
     * so the same barrier and the same number of frames mean the same state.
     */
    private Optional<SubjectState> decode(
            int trackId, int ordinal, RecordedSubject subject, List<RawPacketFrame> base, List<RawPacketFrame> after) {
        CachedState cached = states.get(trackId);
        if (cached != null && cached.ordinal() == ordinal && cached.frames() == after.size()) {
            return cached.state();
        }
        Optional<SubjectState> state;
        if (base.isEmpty() && after.isEmpty()) {
            // Outside the recording's scope at this position.
            state = Optional.empty();
        } else {
            List<RawPacketFrame> frames = new ArrayList<>(base.size() + after.size());
            frames.addAll(base);
            frames.addAll(after);
            state = bridge.decodeState(subject, frames);
        }
        states.put(trackId, new CachedState(ordinal, after.size(), state));
        return state;
    }

    /** The checkpoint of a track at the barrier with this ordinal. */
    private record CachedCheckpoint(int ordinal, CompletableFuture<List<RawPacketFrame>> frames) {
    }

    /** The state decoded from a barrier's checkpoint and this many frames after it. */
    private record CachedState(int ordinal, int frames, Optional<SubjectState> state) {
    }

    /** The block states decoded so far from a barrier's checkpoint and this many frames after it. */
    private record CachedBlocks(int ordinal, int frames, Map<BlockPosition, Optional<SubjectState>> states) {
    }

    /** Adds the state track subject to an entity, when the recording has one for it. */
    dev.voldechse.replayframework.api.playback.RecordedEntity withSubject(
            dev.voldechse.replayframework.api.playback.RecordedEntity entity) {
        for (TrackDescriptor track : tracks.values()) {
            Optional<TrackSubject> subject = track.subject();
            if (subject.isPresent() && subject.get().id().equals(entity.id())) {
                return entity.withSubject(Optional.of(new RecordedSubject(
                        subject.get().kind() == TrackSubject.Kind.PLAYER
                                ? RecordedSubject.Kind.PLAYER
                                : RecordedSubject.Kind.ENTITY,
                        subject.get().id())));
            }
        }
        return entity;
    }

    @Override
    public void close() {
        closed = true;
        open.values().forEach(track -> track.thenAccept(opened -> opened.data().close()));
        open.clear();
        checkpoints.clear();
        states.clear();
        blocks.clear();
    }

    private CompletableFuture<MergedTrackData.Track> open(TrackDescriptor track) {
        return open.computeIfAbsent(track.id(), ignored -> {
            CompletableFuture<MergedTrackData.Track> opening = opener.apply(track).toCompletableFuture();
            opening.whenComplete((opened, failure) -> {
                if (failure != null) {
                    open.remove(track.id(), opening);
                }
            });
            return opening;
        });
    }
}
