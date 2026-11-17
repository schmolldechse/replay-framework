package dev.voldechse.replayframework.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackFramesTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final TrackDescriptor GLOBAL = TrackDescriptor.global(0);
    private static final TrackDescriptor OVERWORLD_TRACK = TrackDescriptor.world(1, OVERWORLD);
    private static final TrackDescriptor ALEX = TrackDescriptor.state(
            2, TrackSubject.player(UUID.fromString("00000000-0000-0000-0000-00000000000a")), "Alex");

    @TempDir
    Path directory;

    @Test
    void framesTakeTheWorldOfTheirTrack() throws Exception {
        List<RawPacketFrame> written = List.of(
                frame(100L, 10L, 0, Optional.of(OVERWORLD)),
                frame(100L, 10L, 1, Optional.of(OVERWORLD)),
                frame(50_000_100L, 11L, 0, Optional.of(OVERWORLD)));

        assertEquals(written, roundTrip(OVERWORLD_TRACK, written));
    }

    @Test
    void globalAndStateTracksHoldFramesOfNoWorld() throws Exception {
        List<RawPacketFrame> written = List.of(frame(7L, 1L, 0, Optional.empty()));

        assertEquals(written, roundTrip(GLOBAL, written));
        assertEquals(written, roundTrip(ALEX, written));
    }

    @Test
    void rejectsFramesOfAnotherWorldAndOtherPhases() throws Exception {
        try (ReplaySegmentWriter writer = new ReplaySegmentWriter(
                directory.resolve("0.segment"), "paper-26.2", OVERWORLD_TRACK)) {
            assertThrows(IllegalArgumentException.class,
                    () -> writer.append(frame(0L, 0L, 0, Optional.of(NETHER))));
            assertThrows(IllegalArgumentException.class,
                    () -> writer.append(frame(0L, 0L, 0, Optional.empty())));
            assertThrows(IllegalArgumentException.class, () -> writer.append(new RawPacketFrame(
                    0L, 0L, 0, PacketPhase.CONFIGURATION, 1, new byte[0], Optional.of(OVERWORLD))));
        }
    }

    @Test
    void checkpointsTakeTheWorldOfTheirTrack() throws Exception {
        ReplayCheckpoint written = new ReplayCheckpoint(0, 0L, List.of(
                frame(0L, 3L, 0, Optional.of(OVERWORLD)),
                frame(0L, 3L, 1, Optional.of(OVERWORLD))));
        Path target = directory.resolve("0.checkpoint");
        new ReplayCheckpointWriter(target, "paper-26.2", OVERWORLD_TRACK).write(written);

        assertEquals(written, new ReplayCheckpointReader().read(target, OVERWORLD_TRACK));
    }

    @Test
    void storesTimeAndTickAsChangesSoAFrameHeaderStaysSmall() throws Exception {
        Path target = directory.resolve("1.segment");
        long firstTime = 3_600_000_000_000L;
        SegmentHeader header;
        try (ReplaySegmentWriter writer = new ReplaySegmentWriter(target, "paper-26.2", OVERWORLD_TRACK)) {
            writer.append(frame(firstTime, 72_000L, 0, Optional.of(OVERWORLD)));
            long afterFirst = writer.uncompressedLength();
            writer.append(frame(firstTime + 1_000L, 72_000L, 1, Optional.of(OVERWORLD)));
            long secondRecord = writer.uncompressedLength() - afterFirst;
            // length, time change, tick change, sequence, packet ID, payload length: one byte
            // each except the two-byte time change, followed by three payload bytes.
            assertEquals(10L, secondRecord);
            header = writer.finish();
        }
        assertEquals(2L, header.frameCount());
    }

    @Test
    void decodesATickThatMovesBackAtALaterTime() throws Exception {
        List<RawPacketFrame> written = List.of(
                frame(100L, 20L, 0, Optional.empty()),
                frame(200L, 19L, 0, Optional.empty()));

        assertEquals(written, roundTrip(GLOBAL, written));
    }

    @Test
    void appliesInItsOwnWorldOrInEveryWorldWithoutOne() {
        assertTrue(frame(0L, 0L, 0, Optional.of(NETHER)).appliesTo(NETHER));
        assertFalse(frame(0L, 0L, 0, Optional.of(NETHER)).appliesTo(OVERWORLD));
        assertTrue(frame(0L, 0L, 0, Optional.empty()).appliesTo(OVERWORLD));
    }

    @Test
    void descriptorsMatchTheirKind() {
        assertThrows(IllegalArgumentException.class, () -> new TrackDescriptor(
                0, TrackKind.WORLD, Optional.empty(), Optional.empty(), ""));
        assertThrows(IllegalArgumentException.class, () -> new TrackDescriptor(
                0, TrackKind.GLOBAL, Optional.of(OVERWORLD), Optional.empty(), ""));
        assertThrows(IllegalArgumentException.class, () -> new TrackDescriptor(
                0, TrackKind.STATE, Optional.of(OVERWORLD), ALEX.subject(), ""));
        assertThrows(IllegalArgumentException.class, () -> TrackDescriptor.world(0, " "));
        assertEquals("tracks/2", ALEX.directory());
    }

    @Test
    void manifestRoundTripsTracksAndBindsEveryFileToOne() throws Exception {
        ReplayManifest manifest = manifest(List.of(GLOBAL, OVERWORLD_TRACK, ALEX), List.of(
                file(ReplayManifest.ArtifactType.INDEX, "tracks/0/index.bin"),
                file(ReplayManifest.ArtifactType.INDEX, "tracks/1/index.bin"),
                file(ReplayManifest.ArtifactType.SEGMENT, "tracks/1/segments/00000000.segment"),
                file(ReplayManifest.ArtifactType.INDEX, "tracks/2/index.bin"),
                file(ReplayManifest.ArtifactType.CHECKPOINT, "tracks/2/checkpoints/00000000.checkpoint")));
        ReplayManifestCodec codec = new ReplayManifestCodec(new GsonBuilder().create());

        ReplayManifest decoded = codec.decode(codec.encode(manifest));

        assertEquals(manifest, decoded);
        assertEquals(Optional.of(ALEX), decoded.track(2));
        assertEquals(2, decoded.files(OVERWORLD_TRACK).size());
        assertEquals("segments/00000000.segment", decoded.files(OVERWORLD_TRACK).get(1).trackPath());
    }

    @Test
    void manifestRejectsInconsistentTracks() {
        var index0 = file(ReplayManifest.ArtifactType.INDEX, "tracks/0/index.bin");
        assertThrows(IllegalArgumentException.class,
                () -> manifest(List.of(GLOBAL), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> manifest(List.of(GLOBAL), List.of(index0,
                        file(ReplayManifest.ArtifactType.INDEX, "tracks/9/index.bin"))));
        assertThrows(IllegalArgumentException.class,
                () -> manifest(List.of(GLOBAL, TrackDescriptor.global(1)), List.of(index0,
                        file(ReplayManifest.ArtifactType.INDEX, "tracks/1/index.bin"))));
        assertThrows(IllegalArgumentException.class,
                () -> file(ReplayManifest.ArtifactType.INDEX, "index.bin"));
        assertThrows(IllegalArgumentException.class,
                () -> file(ReplayManifest.ArtifactType.SEGMENT, "tracks/01/segments/0.segment"));
    }

    private List<RawPacketFrame> roundTrip(TrackDescriptor track, List<RawPacketFrame> frames)
            throws Exception {
        Path target = directory.resolve(track.id() + "-" + System.nanoTime() + ".segment");
        try (ReplaySegmentWriter writer = new ReplaySegmentWriter(target, "paper-26.2", track)) {
            for (RawPacketFrame frame : frames) {
                writer.append(frame);
            }
            writer.finish();
        }
        return new ReplaySegmentReader().read(target, track).frames();
    }

    private static ReplayManifest manifest(
            List<TrackDescriptor> tracks, List<ReplayManifest.ArtifactFile> files) {
        return new ReplayManifest(
                "123e4567-e89b-12d3-a456-426614174000", "paper-26.2", 776, "registry",
                ReplayManifest.CURRENT_FORMAT_REVISION, 1_000L, tracks, files);
    }

    private static ReplayManifest.ArtifactFile file(ReplayManifest.ArtifactType type, String path) {
        return new ReplayManifest.ArtifactFile(type, path, 1L, "0".repeat(64));
    }

    private static RawPacketFrame frame(
            long elapsedNanos, long serverTick, int sequence, Optional<String> world) {
        return new RawPacketFrame(
                elapsedNanos, serverTick, sequence, PacketPhase.PLAY, 45, new byte[] {1, 2, 3}, world);
    }
}
