package dev.voldechse.replayframework.core.replay;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.api.replay.ReplaySummary;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.storage.ArtifactKey;
import dev.voldechse.replayframework.storage.ByteRange;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.StagingReplay;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultReplayServiceSummaryTest {

    @Test
    void mapsRepositoryMetricsWithoutReadingReplayStorage() {
        ReplayId replayId = ReplayId.random();
        Instant createdAt = Instant.parse("2026-08-26T10:15:30Z");
        ReplayRepository.ReplayRow row = new ReplayRepository.ReplayRow(
                replayId,
                "Arena finale",
                "Round 3",
                RecordingStatus.AVAILABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "paper-26_2",
                782,
                1,
                ReplayStorageBackend.LOCAL,
                "replays/" + replayId,
                2_500_000_000L,
                4096L,
                12,
                1,
                1,
                createdAt,
                Optional.of(createdAt),
                Optional.of(createdAt.plusSeconds(125)),
                new JsonObject(),
                0,
                1);
        StubRepository repository = new StubRepository(new ReplayPage<>(List.of(row), Optional.of("next")));

        ReplaySummary summary = new DefaultReplayService(repository, new NoopStorage())
                .querySummaries(ReplayQuery.builder().status(RecordingStatus.AVAILABLE).build())
                .toCompletableFuture()
                .join()
                .items()
                .getFirst();

        assertEquals(replayId, summary.replayId());
        assertEquals("Arena finale", summary.title());
        assertEquals("paper-26_2", summary.adapterId());
        assertEquals(java.time.Duration.ofNanos(2_500_000_000L), summary.duration());
        assertEquals(createdAt, summary.createdAt());
        assertEquals(4096L, summary.totalBytes());
        assertEquals(Optional.of("next"), repository.lastPage.nextCursor());
    }

    private static final class StubRepository implements ReplayRepository {
        private final ReplayPage<ReplayRow> page;
        private ReplayPage<ReplayRow> lastPage;

        private StubRepository(ReplayPage<ReplayRow> page) {
            this.page = page;
        }

        @Override
        public CompletionStage<ReplayRow> create(ReplayCreate command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Optional<ReplayRow>> find(ReplayId replayId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<ReplayPage<ReplayRow>> page(ReplayQuery query) {
            lastPage = page;
            return CompletableFuture.completedFuture(page);
        }

        @Override
        public CompletionStage<ReplayRow> transition(ReplayTransition command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopStorage implements ReplayStorage {
        @Override
        public CompletionStage<StagingReplay> stage(ReplayId replayId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> publish(StagingReplay staging) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Path> fetch(
                ReplayId replayId, ArtifactKey key, Optional<ByteRange> range, Path target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> delete(ReplayId replayId) {
            throw new UnsupportedOperationException();
        }
    }
}
