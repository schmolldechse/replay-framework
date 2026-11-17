package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import dev.voldechse.replayframework.format.TrackDescriptor;
import dev.voldechse.replayframework.format.TrackKind;
import dev.voldechse.replayframework.format.TrackSubject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The state tracks of one playback, opened when a subject's state is first
 * asked for and closed with the playback.
 *
 * <p>A state is the checkpoint of the subject's track at the barrier the
 * playback position lies after, followed by the track's frames up to the
 * position. The bridge turns these frames into the version-neutral state.</p>
 */
final class StateTracks implements AutoCloseable {

    private final List<TrackDescriptor> tracks;
    private final ReplayIndex reference;
    private final long durationNanos;
    private final PlaybackBridge bridge;
    private final Function<TrackDescriptor, CompletionStage<MergedTrackData.Track>> opener;
    private final Map<Integer, CompletableFuture<MergedTrackData.Track>> open = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param tracks the replay's tracks; only state tracks are used
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
            PlaybackBridge bridge,
            Function<TrackDescriptor, CompletionStage<MergedTrackData.Track>> opener) {
        this.tracks = tracks.stream().filter(track -> track.kind() == TrackKind.STATE).toList();
        this.reference = Objects.requireNonNull(reference, "reference");
        this.durationNanos = durationNanos;
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** No state tracks; for playbacks built without them. */
    static StateTracks none(PlaybackBridge bridge) {
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
        Optional<TrackDescriptor> track = tracks.stream()
                .filter(candidate -> candidate.subject().orElseThrow().equals(wanted))
                .findFirst();
        if (track.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        SeekPoint barrier = reference.seekCheckpointFloor(position)
                .orElseThrow(() -> new IllegalStateException("replay has no checkpoint before " + position));
        long end = Math.min(position.toNanos() + 1L, durationNanos + 1L);
        return open(track.get()).thenCompose(data -> {
            CompletionStage<List<RawPacketFrame>> checkpoint = data.data().checkpointFrames(data.anchor(barrier));
            CompletionStage<List<RawPacketFrame>> deltas =
                    data.data().framesAfter(data.anchor(barrier), Duration.ofNanos(end));
            return checkpoint.thenCombine(deltas, (base, after) -> {
                if (base.isEmpty() && after.isEmpty()) {
                    // Outside the recording's scope at this position.
                    return Optional.<SubjectState>empty();
                }
                List<RawPacketFrame> frames = new ArrayList<>(base.size() + after.size());
                frames.addAll(base);
                frames.addAll(after);
                return bridge.decodeState(subject, frames);
            });
        });
    }

    /** Adds the state track subject to an entity, when the recording has one for it. */
    dev.voldechse.replayframework.api.playback.RecordedEntity withSubject(
            dev.voldechse.replayframework.api.playback.RecordedEntity entity) {
        for (TrackDescriptor track : tracks) {
            TrackSubject subject = track.subject().orElseThrow();
            if (subject.id().equals(entity.id())) {
                return entity.withSubject(Optional.of(new RecordedSubject(
                        subject.kind() == TrackSubject.Kind.PLAYER
                                ? RecordedSubject.Kind.PLAYER
                                : RecordedSubject.Kind.ENTITY,
                        subject.id())));
            }
        }
        return entity;
    }

    @Override
    public void close() {
        closed = true;
        open.values().forEach(track -> track.thenAccept(opened -> opened.data().close()));
        open.clear();
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
