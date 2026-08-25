package dev.voldechse.replayframework.core.replay;

import com.google.inject.Inject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.replay.ReplayService;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.storage.ReplayStorage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.key.Key;

/** Public replay-catalog facade backed by the internal repository and storage ports. */
public final class DefaultReplayService implements ReplayService {
    private final ReplayRepository repository;
    private final ReplayStorage storage;

    @Inject
    public DefaultReplayService(ReplayRepository repository, ReplayStorage storage) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<Optional<ReplayMetadata>> find(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return repository.find(replayId).thenApply(row -> row.map(DefaultReplayService::metadata));
    }

    @Override
    public CompletionStage<ReplayPage<ReplayMetadata>> query(ReplayQuery query) {
        Objects.requireNonNull(query, "query");
        return repository.page(query).thenApply(page -> new ReplayPage<>(
                page.items().stream().map(DefaultReplayService::metadata).toList(),
                page.nextCursor()));
    }

    @Override
    public CompletionStage<Void> delete(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return repository.transition(new ReplayRepository.ReplayTransition(
                        replayId,
                        dev.voldechse.replayframework.api.recording.RecordingStatus.AVAILABLE,
                        dev.voldechse.replayframework.api.recording.RecordingStatus.DELETING,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()))
                .thenCompose(ignored -> storage.delete(replayId))
                .thenCompose(ignored -> repository.delete(replayId));
    }

    private static ReplayMetadata metadata(ReplayRepository.ReplayRow row) {
        Map<Key, Object> values = new LinkedHashMap<>();
        row.metadata().entrySet().forEach(entry -> values.put(
                Key.key(entry.getKey()),
                entry.getValue().deepCopy()));
        return new ReplayMetadata(
                row.replayId(),
                row.title(),
                row.description(),
                row.metadataRevision(),
                values);
    }
}
