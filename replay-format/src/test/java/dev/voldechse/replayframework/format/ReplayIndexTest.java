package dev.voldechse.replayframework.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIndexTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void seekFloorHandlesBeforeExactBetweenDuplicateAndAfterTargets() {
        SeekPoint first = new SeekPoint(10L, 0, 0, 12L);
        SeekPoint second = new SeekPoint(20L, 1, 1, 24L);
        SeekPoint duplicateTime = new SeekPoint(20L, 2, 2, 36L);
        SeekPoint last = new SeekPoint(30L, 3, 3, 48L);
        ReplayIndex index = ReplayIndex.of(List.of(first, second, duplicateTime, last));

        assertTrue(index.seekFloor(Duration.ofNanos(9L)).isEmpty());
        assertEquals(first, index.seekFloor(Duration.ofNanos(10L)).orElseThrow());
        assertEquals(first, index.seekFloor(Duration.ofNanos(15L)).orElseThrow());
        assertEquals(duplicateTime, index.seekFloor(Duration.ofNanos(20L)).orElseThrow());
        assertEquals(last, index.seekFloor(Duration.ofNanos(100L)).orElseThrow());
    }

    @Test
    void indexRejectsNegativeTargetsAndMutableOrUnorderedPoints() {
        SeekPoint first = new SeekPoint(10L, 0, 0, 12L);
        ReplayIndex index = ReplayIndex.of(List.of(first));

        assertThrows(
                IllegalArgumentException.class,
                () -> index.seekFloor(Duration.ofNanos(-1L)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> index.points().add(new SeekPoint(20L, 1, 1, 24L)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReplayIndex.of(List.of(first, new SeekPoint(9L, 1, 1, 24L))));
    }

    @Test
    void binaryRoundTripPreservesSeekPoints() throws Exception {
        ReplayIndex expected = ReplayIndex.of(List.of(
                new SeekPoint(10L, 0, 0, 12L),
                new SeekPoint(20L, 1, 1, 24L),
                new SeekPoint(30L, 2, 2, 36L)));
        Path target = temporaryDirectory.resolve("index.bin");

        new ReplayIndexWriter().write(target, expected);

        assertEquals(expected.points(), new ReplayIndexReader().read(target).points());
    }

    @Test
    void truncatedAndTrailingIndexBytesFailAsCorruptArtifact() throws Exception {
        ReplayIndex expected = ReplayIndex.of(List.of(new SeekPoint(10L, 0, 0, 12L)));
        Path target = temporaryDirectory.resolve("index.bin");
        new ReplayIndexWriter().write(target, expected);
        byte[] complete = Files.readAllBytes(target);

        Path truncated = temporaryDirectory.resolve("index-truncated.bin");
        Files.write(truncated, java.util.Arrays.copyOf(complete, complete.length - 1));
        assertThrows(
                CorruptReplayArtifactException.class,
                () -> new ReplayIndexReader().read(truncated));

        Path trailing = temporaryDirectory.resolve("index-trailing.bin");
        Files.write(trailing, complete);
        Files.write(trailing, new byte[]{0x42}, java.nio.file.StandardOpenOption.APPEND);
        assertThrows(
                CorruptReplayArtifactException.class,
                () -> new ReplayIndexReader().read(trailing));
    }

    @Test
    void nonCanonicalEntryCountEncodingFailsAsCorruptArtifact() throws Exception {
        Path target = temporaryDirectory.resolve("index-noncanonical.bin");
        Files.write(target, new byte[]{
                'R', 'F', 'I', '1',
                0x01,
                (byte) 0x81, 0x00
        });

        assertThrows(
                CorruptReplayArtifactException.class,
                () -> new ReplayIndexReader().read(target));
    }

    @Test
    void overflowingTenByteVarLongFailsAsCorruptArtifact() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[]{'R', 'F', 'I', '1', 0x01, 0x01});
        for (int index = 0; index < 9; index++) {
            bytes.write(0x80);
        }
        bytes.write(0x02);
        bytes.writeBytes(new byte[]{0x00, 0x00, 0x00});
        Path target = temporaryDirectory.resolve("index-overflow.bin");
        Files.write(target, bytes.toByteArray());

        assertThrows(
                CorruptReplayArtifactException.class,
                () -> new ReplayIndexReader().read(target));
    }
}
