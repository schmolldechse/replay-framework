package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The scene tracks of one playback: the global track and one track per
 * recorded world.
 *
 * <p>The timeline reads only the global track and the track of the world the
 * viewer watches. A world change replaces the world track; the other world
 * tracks stay open but are only read when asked for, such as for the
 * location of players in other worlds. A track reads segments only when its
 * frames are requested.</p>
 */
final class SceneTracks implements AutoCloseable {

    private final MergedTrackData.Track global;
    private final Map<String, MergedTrackData.Track> worlds;
    private final ReplayIndex reference;
    private final long durationNanos;
    private final PlaybackBridge bridge;
    private final MergedTrackData timeline;
    private volatile String viewed;

    /**
     * @param global the global track
     * @param worlds the world tracks by world key
     * @param reference the global track's index; its checkpoint ordinals are those of every track
     * @param durationNanos the replay duration
     * @param bridge locates players in frames of other worlds
     */
    SceneTracks(
            MergedTrackData.Track global,
            Map<String, MergedTrackData.Track> worlds,
            ReplayIndex reference,
            long durationNanos,
            PlaybackBridge bridge) {
        this.global = Objects.requireNonNull(global, "global");
        this.worlds = Map.copyOf(Objects.requireNonNull(worlds, "worlds"));
        this.reference = Objects.requireNonNull(reference, "reference");
        this.durationNanos = durationNanos;
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.timeline = new MergedTrackData(List.of(global));
    }

    /** The data the timeline plays: the global track and the viewed world's track. */
    MergedTrackData timeline() {
        return timeline;
    }

    /** All scene tracks as one, for choosing where the viewer starts. */
    MergedTrackData all() {
        List<MergedTrackData.Track> tracks = new ArrayList<>();
        tracks.add(global);
        tracks.addAll(worlds.values());
        return new MergedTrackData(tracks);
    }

    /** Plays the given world from now on; the caller rebuilds the view afterwards. */
    void view(String world) {
        Objects.requireNonNull(world, "world");
        MergedTrackData.Track track = worlds.get(world);
        viewed = world;
        timeline.tracks(track == null ? List.of(global) : List.of(global, track));
    }

    /**
     * Every player at a position: names and the viewed world's players from
     * the frames played, the players of the other worlds read from their
     * tracks.
     */
    CompletionStage<List<RecordedPlayer>> players(Duration position) {
        List<RecordedPlayer> played = bridge.recordedPlayers();
        SeekPoint barrier = reference.seekCheckpointFloor(position)
                .orElseThrow(() -> new IllegalStateException("replay has no checkpoint before " + position));
        Duration end = Duration.ofNanos(Math.min(position.toNanos() + 1L, durationNanos + 1L));
        List<CompletableFuture<List<RecordedPlayer>>> others = new ArrayList<>();
        for (Map.Entry<String, MergedTrackData.Track> world : worlds.entrySet()) {
            if (world.getKey().equals(viewed)) {
                continue;
            }
            MergedTrackData.Track track = world.getValue();
            SeekPoint anchor = track.anchor(barrier);
            others.add(track.data().checkpointFrames(anchor)
                    .thenCombine(track.data().framesAfter(anchor, end), (base, after) -> {
                        List<RawPacketFrame> frames = new ArrayList<>(base.size() + after.size());
                        frames.addAll(base);
                        frames.addAll(after);
                        return bridge.players(frames);
                    })
                    .toCompletableFuture());
        }
        return CompletableFuture.allOf(others.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> merge(played, others.stream().map(CompletableFuture::join).toList()));
    }

    @Override
    public void close() {
        global.data().close();
        worlds.values().forEach(track -> track.data().close());
    }

    /** Played players keep their names; a location read from another world fills in where none is known. */
    static List<RecordedPlayer> merge(List<RecordedPlayer> played, List<List<RecordedPlayer>> others) {
        Map<UUID, RecordedPlayer> players = new LinkedHashMap<>();
        played.forEach(player -> players.put(player.id(), player));
        for (List<RecordedPlayer> world : others) {
            for (RecordedPlayer found : world) {
                if (found.location().isEmpty()) {
                    continue;
                }
                RecordedPlayer known = players.get(found.id());
                if (known == null) {
                    players.put(found.id(), found);
                } else if (known.location().isEmpty()) {
                    players.put(found.id(), new RecordedPlayer(known.id(), known.name(), found.location()));
                }
            }
        }
        List<RecordedPlayer> result = new ArrayList<>(players.values());
        result.sort(Comparator.comparing(RecordedPlayer::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    /** The viewed world, once one is chosen. */
    Optional<String> viewed() {
        return Optional.ofNullable(viewed);
    }
}
