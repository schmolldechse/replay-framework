package dev.voldechse.replayframework.core.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.core.playback.buffer.PrefetchPlanner;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayIndex;
import dev.voldechse.replayframework.format.SeekPoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class SceneTracksTest {
    private static final ReplayIndex INDEX = ReplayIndex.of(List.of(new SeekPoint(0L, 0, 0, 0L)));
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void theTimelinePlaysOnlyTheGlobalTrackAndTheViewedWorld() {
        FakeTrack global = new FakeTrack(List.of(frame(10L, 1)));
        FakeTrack overworld = new FakeTrack(List.of(frame(20L, 2)));
        FakeTrack nether = new FakeTrack(List.of(frame(30L, 3)));
        SceneTracks scene = scene(global, Map.of("minecraft:overworld", overworld, "minecraft:the_nether", nether),
                new FakeBridge());

        assertEquals(List.of(1), sequences(scene.timeline()));
        scene.view("minecraft:overworld");
        assertEquals(List.of(1, 2), sequences(scene.timeline()));
        scene.view("minecraft:the_nether");
        assertEquals(List.of(1, 3), sequences(scene.timeline()));
        assertEquals(List.of(1, 2, 3), sequences(scene.all()));
    }

    @Test
    void readsPlayersOfOtherWorldsFromTheirTracksOnly() {
        FakeTrack global = new FakeTrack(List.of());
        FakeTrack overworld = new FakeTrack(List.of(frame(20L, 2)));
        FakeTrack nether = new FakeTrack(List.of(frame(30L, 3)));
        Map<String, FakeTrack> worlds = new LinkedHashMap<>();
        worlds.put("minecraft:overworld", overworld);
        worlds.put("minecraft:the_nether", nether);
        FakeBridge bridge = new FakeBridge();
        bridge.played = List.of(
                new RecordedPlayer(ALEX, "Alex", Optional.of(location("minecraft:overworld"))),
                new RecordedPlayer(STEVE, "Steve", Optional.empty()));
        bridge.found = List.of(new RecordedPlayer(STEVE, STEVE.toString(), Optional.of(location("minecraft:the_nether"))));
        SceneTracks scene = scene(global, worlds, bridge);
        scene.view("minecraft:overworld");

        List<RecordedPlayer> players = scene.players(Duration.ofNanos(40L)).toCompletableFuture().join();

        assertEquals(List.of(List.of(3)), bridge.scanned);
        assertNull(overworld.requestedAfter);
        assertEquals(new SeekPoint(0L, 0, 0, 0L), nether.requestedAfter);
        assertEquals(List.of("Alex", "Steve"), players.stream().map(RecordedPlayer::name).toList());
        assertEquals(Key.key("minecraft:the_nether"), players.get(1).location().orElseThrow().world());
    }

    @Test
    void aPlayedLocationWinsOverOneReadFromAnotherWorld() {
        RecordedPlayer played = new RecordedPlayer(ALEX, "Alex", Optional.of(location("minecraft:overworld")));
        RecordedPlayer found = new RecordedPlayer(ALEX, ALEX.toString(), Optional.of(location("minecraft:the_nether")));
        RecordedPlayer unknown = new RecordedPlayer(STEVE, STEVE.toString(), Optional.empty());

        List<RecordedPlayer> merged = SceneTracks.merge(List.of(played), List.of(List.of(found, unknown)));

        assertEquals(List.of(played), merged);
        assertTrue(merged.getFirst().inScene());
    }

    private static SceneTracks scene(FakeTrack global, Map<String, FakeTrack> worlds, FakeBridge bridge) {
        Map<String, MergedTrackData.Track> tracks = new LinkedHashMap<>();
        worlds.forEach((world, track) -> tracks.put(world, MergedTrackData.Track.of(track, INDEX)));
        return new SceneTracks(MergedTrackData.Track.of(global, INDEX), tracks, INDEX, 100L, bridge);
    }

    private static List<Integer> sequences(MergedTrackData data) {
        return data.framesBetween(Duration.ZERO, Duration.ofNanos(100L)).toCompletableFuture().join()
                .stream().map(RawPacketFrame::sequence).toList();
    }

    private static RecordedPlayer.Location location(String world) {
        return new RecordedPlayer.Location(Key.key(world), 1.0, 64.0, 2.0, 0.0F, 0.0F);
    }

    private static RawPacketFrame frame(long elapsedNanos, int sequence) {
        return new RawPacketFrame(elapsedNanos, elapsedNanos / 10L, sequence, PacketPhase.PLAY, 1, new byte[0]);
    }

    private static final class FakeBridge implements PlaybackBridge {
        private List<RecordedPlayer> played = List.of();
        private List<RecordedPlayer> found = List.of();
        private final List<List<Integer>> scanned = new ArrayList<>();

        @Override
        public void setIdentityContext(PlaybackIdentityContext context) {
        }

        @Override
        public void send(RawPacketFrame frame) {
        }

        @Override
        public void resetView() {
        }

        @Override
        public List<RecordedPlayer> recordedPlayers() {
            return played;
        }

        @Override
        public List<RecordedPlayer> players(List<RawPacketFrame> frames) {
            scanned.add(frames.stream().map(RawPacketFrame::sequence).toList());
            return found;
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeTrack implements PlaybackTimeline.PlaybackData {
        private final List<RawPacketFrame> frames;
        private SeekPoint requestedAfter;

        private FakeTrack(List<RawPacketFrame> frames) {
            this.frames = frames;
        }

        @Override
        public CompletionStage<PlaybackTimeline.BufferProgress> ensureAvailable(
                Duration position, PlaybackSpeed speed, PrefetchPlanner.Direction direction) {
            return CompletableFuture.completedFuture(
                    new PlaybackTimeline.BufferProgress(Duration.ZERO, Duration.ZERO, true));
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> checkpointFrames(SeekPoint point) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesBetween(Duration startInclusive, Duration endInclusive) {
            return CompletableFuture.completedFuture(frames);
        }

        @Override
        public CompletionStage<List<RawPacketFrame>> framesAfter(SeekPoint checkpoint, Duration endExclusive) {
            requestedAfter = checkpoint;
            return CompletableFuture.completedFuture(frames);
        }

        @Override
        public PlaybackTimeline.BufferProgress progress(Duration position) {
            return new PlaybackTimeline.BufferProgress(Duration.ZERO, Duration.ZERO, true);
        }

        @Override
        public void close() {
        }
    }
}
