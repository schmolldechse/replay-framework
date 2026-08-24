package dev.voldechse.replayframework.database.postgresql.query;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.query.MetadataPredicate;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.core.metadata.MetadataRegistry;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.key.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresReplayQueryCompilerTest {

    @Test
    void compilesEqualityAsBoundJsonbContainmentParameter() {
        ReplayMetadataKey<String> arenaKey = arenaKey();
        MetadataRegistry registry = new MetadataRegistry();
        registry.register(arenaKey);
        PostgresReplayQueryCompiler compiler = new PostgresReplayQueryCompiler(registry, new Gson());

        PostgresReplayQueryCompiler.CompiledQuery compiled = compiler.compile(
                ReplayQuery.builder()
                        .where(arenaKey, MetadataPredicate.equalTo("castle"))
                        .build());

        assertTrue(compiled.whereClause().contains("@>"));
        assertEquals(1, compiled.parameters().size());
        PostgresReplayQueryCompiler.Parameter parameter = compiled.parameters().get(0);
        assertEquals(PostgresReplayQueryCompiler.ParameterKind.JSONB, parameter.kind());
        JsonObject object = assertInstanceOf(JsonObject.class, parameter.value());
        assertEquals("castle", object.get("example:arena").getAsString());
    }

    @Test
    void rejectsMetadataQueryForUnregisteredKey() {
        ReplayMetadataKey<String> arenaKey = arenaKey();
        PostgresReplayQueryCompiler compiler = new PostgresReplayQueryCompiler(
                new MetadataRegistry(), new Gson());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> compiler.compile(ReplayQuery.builder()
                        .where(arenaKey, MetadataPredicate.equalTo("castle"))
                        .build()));

        assertTrue(exception.getMessage().contains("example:arena"));
    }

    @Test
    void compilesFixedSortCursorWithTypedInstantParameter() {
        MetadataRegistry registry = new MetadataRegistry();
        PostgresReplayQueryCompiler compiler = new PostgresReplayQueryCompiler(registry, new Gson());
        ReplayQuery firstPage = ReplayQuery.builder()
                .orderBy(ReplayQuery.SortField.CREATED_AT, ReplayQuery.SortDirection.DESCENDING)
                .build();
        ReplayRepository.ReplayRow row = row(Instant.parse("2026-08-24T12:00:00Z"));

        String cursor = compiler.encodeCursor(firstPage, row);
        PostgresReplayQueryCompiler.CompiledQuery nextPage = compiler.compile(
                ReplayQuery.builder()
                        .orderBy(ReplayQuery.SortField.CREATED_AT,
                                ReplayQuery.SortDirection.DESCENDING)
                        .cursor(cursor)
                        .build());

        assertTrue(nextPage.whereClause().contains(":cursor_0"));
        PostgresReplayQueryCompiler.Parameter cursorParameter = nextPage.parameters().stream()
                .filter(parameter -> parameter.name().equals("cursor_0"))
                .findFirst()
                .orElseThrow();
        assertEquals(PostgresReplayQueryCompiler.ParameterKind.INSTANT, cursorParameter.kind());
        assertEquals(Instant.parse("2026-08-24T12:00:00Z"), cursorParameter.value());
    }

    @Test
    void compilesMissingMetadataSortCursorWithNullsLastPredicate() {
        ReplayMetadataKey<String> arenaKey = arenaKey();
        MetadataRegistry registry = new MetadataRegistry();
        registry.register(arenaKey);
        PostgresReplayQueryCompiler compiler = new PostgresReplayQueryCompiler(registry, new Gson());
        ReplayQuery firstPage = ReplayQuery.builder()
                .orderBy(arenaKey, ReplayQuery.SortDirection.ASCENDING)
                .build();

        String cursor = compiler.encodeCursor(firstPage, row(Instant.parse("2026-08-24T12:00:00Z")));
        PostgresReplayQueryCompiler.CompiledQuery nextPage = compiler.compile(
                ReplayQuery.builder()
                        .orderBy(arenaKey, ReplayQuery.SortDirection.ASCENDING)
                        .cursor(cursor)
                        .build());

        assertTrue(nextPage.whereClause().contains("IS NULL"));
        assertTrue(nextPage.whereClause().contains("sort_key_0"));
    }

    private static ReplayMetadataKey<String> arenaKey() {
        TypeAdapter<String> codec = new Gson().getAdapter(String.class).nullSafe();
        return ReplayMetadataKey.of(
                Key.key("example:arena"),
                TypeToken.get(String.class),
                codec,
                QueryCapabilities.of(
                        QueryCapabilities.Operation.EQUALITY,
                        new QueryCapabilities.Operation[]{
                                QueryCapabilities.Operation.EXISTENCE,
                                QueryCapabilities.Operation.ORDERING}));
    }

    private static ReplayRepository.ReplayRow row(Instant createdAt) {
        return new ReplayRepository.ReplayRow(
                ReplayId.random(),
                "title",
                "description",
                RecordingStatus.AVAILABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "paper-26_2",
                774,
                1,
                ReplayStorageBackend.LOCAL,
                "test/replay",
                0,
                0,
                0,
                0,
                0,
                createdAt,
                Optional.empty(),
                Optional.of(createdAt),
                new JsonObject(),
                0,
                0);
    }
}
