package dev.voldechse.replayframework.core.port;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.MetadataRevisionOperation;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepositoryPortValidationTest {

    @Test
    void replayCreateRejectsBlankTitle() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayRepository.ReplayCreate(
                        ReplayId.random(),
                        " ",
                        "description",
                        "paper-26_2",
                        1,
                        1,
                        ReplayStorageBackend.LOCAL,
                        "replays/id",
                        Instant.now(),
                        new JsonObject()));
    }

    @Test
    void transitionRejectsSkippedLifecycleState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayRepository.ReplayTransition(
                        ReplayId.random(),
                        RecordingStatus.INITIALIZING,
                        RecordingStatus.AVAILABLE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void metadataWriteRequiresConsecutiveRevision() {
        ReplayId replayId = ReplayId.random();
        MetadataRepository.MetadataSnapshot snapshot =
                new MetadataRepository.MetadataSnapshot(
                        replayId,
                        "title",
                        "description",
                        2,
                        new JsonObject());

        assertThrows(
                IllegalArgumentException.class,
                () -> new MetadataRepository.MetadataWrite(
                        replayId,
                        0,
                        snapshot,
                        MetadataRevisionOperation.SET,
                        Instant.now()));
    }

    @Test
    void leaseRejectsExpiryBeforeHeartbeat() {
        Instant acquired = Instant.parse("2026-08-24T10:00:00Z");
        Instant heartbeat = acquired.plusSeconds(10);

        assertThrows(
                IllegalArgumentException.class,
                () -> new LeaseRepository.LeaseAcquire(
                        ReplayId.random(),
                        UUID.randomUUID(),
                        acquired,
                        heartbeat,
                        heartbeat));
    }

    @Test
    void failedTransitionRequiresFailureDetails() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayRepository.ReplayTransition(
                        ReplayId.random(),
                        RecordingStatus.RECORDING,
                        RecordingStatus.FAILED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void failureDetailsRequireNonBlankSafeDescription() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplayRepository.FailureDetails(
                        ReplayFailureCode.INTERNAL_ERROR,
                        " ",
                        Instant.now()));
    }
}
