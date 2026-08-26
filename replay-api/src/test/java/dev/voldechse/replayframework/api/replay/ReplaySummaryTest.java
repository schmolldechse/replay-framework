package dev.voldechse.replayframework.api.replay;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplaySummaryTest {

    @Test
    void preservesCatalogValuesForReadOnlyProjection() {
        ReplayId replayId = new ReplayId(UUID.fromString("00000000-0000-0000-0000-000000000032"));
        Instant createdAt = Instant.parse("2026-08-26T10:15:30Z");

        ReplaySummary summary = new ReplaySummary(
                replayId,
                "Arena finale",
                "Round 3",
                RecordingStatus.AVAILABLE,
                "paper-26_2",
                Duration.ofSeconds(125),
                createdAt,
                4096L);

        assertEquals(replayId, summary.replayId());
        assertEquals("Arena finale", summary.title());
        assertEquals("Round 3", summary.description());
        assertEquals(RecordingStatus.AVAILABLE, summary.status());
        assertEquals("paper-26_2", summary.adapterId());
        assertEquals(Duration.ofSeconds(125), summary.duration());
        assertEquals(createdAt, summary.createdAt());
        assertEquals(4096L, summary.totalBytes());
    }

    @Test
    void rejectsInvalidSummaryValuesBeforeTheyReachTheExampleUi() {
        ReplayId replayId = ReplayId.random();
        Instant createdAt = Instant.parse("2026-08-26T10:15:30Z");

        assertThrows(NullPointerException.class, () -> new ReplaySummary(
                replayId, "Title", "Description", RecordingStatus.AVAILABLE,
                "paper-26_2", null, createdAt, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReplaySummary(
                replayId, " ", "Description", RecordingStatus.AVAILABLE,
                "paper-26_2", Duration.ZERO, createdAt, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReplaySummary(
                replayId, "Title", "Description", RecordingStatus.AVAILABLE,
                "paper-26_2", Duration.ofSeconds(-1), createdAt, 1));
        assertThrows(IllegalArgumentException.class, () -> new ReplaySummary(
                replayId, "Title", "Description", RecordingStatus.AVAILABLE,
                "paper-26_2", Duration.ZERO, createdAt, -1));
    }
}
