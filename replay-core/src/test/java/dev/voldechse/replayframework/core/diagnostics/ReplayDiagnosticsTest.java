package dev.voldechse.replayframework.core.diagnostics;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReplayDiagnosticsTest {

    @Test
    void snapshotDefensivelyCopiesAndSortsStateLists() {
        RecordingSessionId first = new RecordingSessionId(UUID.fromString(
                "00000000-0000-0000-0000-000000000002"));
        RecordingSessionId second = new RecordingSessionId(UUID.fromString(
                "00000000-0000-0000-0000-000000000001"));
        List<ReplayDiagnosticsSnapshot.RecordingState> recordings = new ArrayList<>(List.of(
                recording(second), recording(first)));
        List<ReplayDiagnosticsSnapshot.PlaybackState> playbacks = new ArrayList<>(List.of(
                playback(PlaybackSessionId.random())));

        ReplayDiagnosticsSnapshot snapshot = new ReplayDiagnosticsSnapshot(
                Instant.now(),
                recordings,
                playbacks,
                12L,
                3L,
                4L,
                5L,
                6L,
                Optional.of(Duration.ofMillis(7)));

        recordings.clear();
        playbacks.clear();

        assertEquals(List.of(second, first), snapshot.recordings().stream()
                .map(ReplayDiagnosticsSnapshot.RecordingState::sessionId)
                .toList());
        assertFalse(snapshot.playbacks().isEmpty());
        assertEquals(12L, snapshot.queuedBytes());
    }

    @Test
    void countersAndStorageOperationRemainNonNegativeAndCloseOnce() {
        ReplayDiagnostics diagnostics = new ReplayDiagnostics();
        diagnostics.packetCaptured(4L);
        diagnostics.cacheHit();
        diagnostics.cacheMiss();
        ReplayDiagnostics.StorageOperation operation = diagnostics.beginStorageOperation(128L);
        operation.complete();
        operation.fail();

        ReplayDiagnosticsSnapshot snapshot = diagnostics.snapshot();

        assertEquals(0L, snapshot.packetsPerSecond());
        assertEquals(1L, snapshot.cacheHits());
        assertEquals(1L, snapshot.cacheMisses());
        assertEquals(0L, snapshot.pendingUploadBytes());
        assertFalse(snapshot.lastStorageLatency().isEmpty());
    }

    private static ReplayDiagnosticsSnapshot.RecordingState recording(RecordingSessionId id) {
        return new ReplayDiagnosticsSnapshot.RecordingState(
                id,
                ReplayId.random(),
                RecordingStatus.RECORDING,
                Duration.ZERO,
                0L,
                0L,
                0L);
    }

    private static ReplayDiagnosticsSnapshot.PlaybackState playback(PlaybackSessionId id) {
        return new ReplayDiagnosticsSnapshot.PlaybackState(
                id,
                ReplayId.random(),
                UUID.randomUUID(),
                new PlaybackSnapshot(
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        PlaybackSpeed.NORMAL,
                        PlaybackStatus.PAUSED,
                        Duration.ZERO,
                        Duration.ZERO));
    }
}
