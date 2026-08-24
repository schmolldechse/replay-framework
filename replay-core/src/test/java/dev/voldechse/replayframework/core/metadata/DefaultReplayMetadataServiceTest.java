package dev.voldechse.replayframework.core.metadata;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.MetadataMutation;
import dev.voldechse.replayframework.api.metadata.MetadataRevisionOperation;
import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.core.port.MetadataRepository;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultReplayMetadataServiceTest {

    private static final TypeAdapter<String> STRING_CODEC =
            new Gson().getAdapter(String.class).nullSafe();
    private static final ReplayMetadataKey<String> ARENA_KEY = ReplayMetadataKey.of(
            Key.key("example:arena"),
            TypeToken.get(String.class),
            STRING_CODEC,
            QueryCapabilities.of(
                    QueryCapabilities.Operation.EQUALITY,
                    new QueryCapabilities.Operation[]{QueryCapabilities.Operation.EXISTENCE}));

    @Test
    void decodesRegisteredJsonValuesIntoTypedMetadata() {
        ReplayId replayId = ReplayId.random();
        JsonObject values = new JsonObject();
        values.addProperty("example:arena", "castle");
        InMemoryMetadataRepository repository = new InMemoryMetadataRepository(
                new MetadataRepository.MetadataSnapshot(
                        replayId, "Replay", "Description", 0L, values));
        DefaultReplayMetadataService service = service(repository);

        ReplayMetadata metadata = service.get(replayId).toCompletableFuture().join();

        assertEquals("castle", metadata.value(ARENA_KEY).orElseThrow());
        assertEquals(0L, metadata.revision());
    }

    @Test
    void appliesTypedMutationAsNextRevisionWithCompletePortSnapshot() {
        ReplayId replayId = ReplayId.random();
        InMemoryMetadataRepository repository = new InMemoryMetadataRepository(
                new MetadataRepository.MetadataSnapshot(
                        replayId, "Replay", "Description", 0L, new JsonObject()));
        DefaultReplayMetadataService service = service(repository);

        ReplayMetadata result = service.apply(MetadataMutation.builder(replayId)
                .expectedRevision(0L)
                .put(ARENA_KEY, "castle")
                .build()).toCompletableFuture().join();

        assertEquals(1L, result.revision());
        assertEquals("castle", result.value(ARENA_KEY).orElseThrow());
        assertEquals(1L, repository.lastWrite.nextSnapshot().revision());
        assertEquals(MetadataRevisionOperation.SET, repository.lastWrite.operation());
        assertEquals("castle", repository.lastWrite.nextSnapshot().values()
                .get("example:arena").getAsString());
    }

    @Test
    void rejectsStaleMutationBeforeRepositoryApply() {
        ReplayId replayId = ReplayId.random();
        InMemoryMetadataRepository repository = new InMemoryMetadataRepository(
                new MetadataRepository.MetadataSnapshot(
                        replayId, "Replay", "Description", 2L, new JsonObject()));
        DefaultReplayMetadataService service = service(repository);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> service.apply(MetadataMutation.builder(replayId)
                        .expectedRevision(1L)
                        .description("stale")
                        .build())
                        .toCompletableFuture()
                        .join());

        assertInstanceOf(
                MetadataRepository.MetadataRevisionConflictException.class,
                exception.getCause());
        assertEquals(0, repository.applyCount);
    }

    private static DefaultReplayMetadataService service(InMemoryMetadataRepository repository) {
        MetadataRegistry registry = new MetadataRegistry();
        registry.register(ARENA_KEY);
        return new DefaultReplayMetadataService(repository, registry);
    }

    private static final class InMemoryMetadataRepository implements MetadataRepository {
        private MetadataSnapshot current;
        private final List<MetadataRevisionRow> revisions = new ArrayList<>();
        private MetadataWrite lastWrite;
        private int applyCount;

        private InMemoryMetadataRepository(MetadataSnapshot current) {
            this.current = current;
        }

        @Override
        public CompletableFuture<Optional<MetadataSnapshot>> current(ReplayId replayId) {
            return CompletableFuture.completedFuture(
                    current.replayId().equals(replayId) ? Optional.of(current) : Optional.empty());
        }

        @Override
        public CompletableFuture<List<MetadataRevisionRow>> history(ReplayId replayId) {
            return CompletableFuture.completedFuture(List.copyOf(revisions));
        }

        @Override
        public CompletableFuture<MetadataSnapshot> apply(MetadataWrite command) {
            applyCount++;
            lastWrite = command;
            current = command.nextSnapshot();
            revisions.add(new MetadataRevisionRow(
                    command.nextSnapshot().revision(),
                    command.committedAt(),
                    command.operation(),
                    command.nextSnapshot()));
            return CompletableFuture.completedFuture(current);
        }
    }
}
