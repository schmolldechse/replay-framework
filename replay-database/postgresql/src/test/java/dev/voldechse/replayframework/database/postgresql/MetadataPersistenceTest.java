package dev.voldechse.replayframework.database.postgresql;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.zaxxer.hikari.HikariDataSource;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.MetadataRevisionOperation;
import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.query.MetadataPredicate;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.core.metadata.DefaultReplayMetadataService;
import dev.voldechse.replayframework.core.metadata.MetadataRegistry;
import dev.voldechse.replayframework.core.port.MetadataRepository;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused PostgreSQL contract test for native enums, atomic metadata revisions and JSONB queries.
 *
 * <p>Set {@code REPLAY_POSTGRES_JDBC_URL}, {@code REPLAY_POSTGRES_USERNAME} and
 * {@code REPLAY_POSTGRES_PASSWORD} to run it against a disposable PostgreSQL database.</p>
 */
class MetadataPersistenceTest {
    private Injector injector;
    private HibernateSessionFactory sessionFactory;
    private HikariDataSource dataSource;
    private ReplayRepository replayRepository;
    private MetadataRepository metadataRepository;
    private DefaultReplayMetadataService metadataService;
    private ReplayId replayId;

    @BeforeEach
    void setUp() {
        String jdbcUrl = System.getenv("REPLAY_POSTGRES_JDBC_URL");
        Assumptions.assumeTrue(
                jdbcUrl != null && !jdbcUrl.isBlank(),
                "REPLAY_POSTGRES_JDBC_URL is not configured");

        String username = System.getenv().getOrDefault("REPLAY_POSTGRES_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("REPLAY_POSTGRES_PASSWORD", "postgres");
        PostgresModule.Configuration configuration = new PostgresModule.Configuration(
                jdbcUrl,
                username,
                password,
                0,
                4,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                2,
                16);
        injector = Guice.createInjector(new PostgresModule(configuration, new Gson()));
        sessionFactory = injector.getInstance(HibernateSessionFactory.class);
        dataSource = injector.getInstance(HikariDataSource.class);
        replayRepository = injector.getInstance(ReplayRepository.class);
        metadataRepository = injector.getInstance(MetadataRepository.class);
        MetadataRegistry registry = injector.getInstance(MetadataRegistry.class);
        metadataService = new DefaultReplayMetadataService(metadataRepository, registry);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (replayId != null && dataSource != null) {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "delete from replay where id = ?")) {
                statement.setObject(1, replayId.value());
                statement.executeUpdate();
            }
        }
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    void persistsNativeEnumsRevisionHistoryAndTypedJsonbQuery() throws Exception {
        ReplayMetadataKey<String> arenaKey = stringKey("example:arena");
        metadataService.register(arenaKey);
        replayId = ReplayId.random();
        replayRepository.create(new ReplayRepository.ReplayCreate(
                replayId,
                "Integration replay",
                "metadata persistence",
                "paper-26_2",
                774,
                1,
                ReplayStorageBackend.LOCAL,
                "integration/" + replayId,
                Instant.parse("2026-08-24T12:00:00Z"),
                new com.google.gson.JsonObject())).toCompletableFuture().join();

        var initial = metadataService.get(replayId).toCompletableFuture().join();
        var updated = metadataService.apply(
                dev.voldechse.replayframework.api.metadata.MetadataMutation.builder(replayId)
                        .expectedRevision(initial.revision())
                        .put(arenaKey, "castle")
                        .build()).toCompletableFuture().join();

        assertEquals(1L, updated.revision());
        assertEquals("castle", updated.value(arenaKey).orElseThrow());
        assertEquals(
                List.of(MetadataRevisionOperation.SET),
                metadataService.history(replayId).toCompletableFuture().join().stream()
                        .map(revision -> revision.operation())
                        .toList());

        var page = replayRepository.page(ReplayQuery.builder()
                .where(arenaKey, MetadataPredicate.equalTo("castle"))
                .build()).toCompletableFuture().join();
        assertEquals(List.of(replayId), page.items().stream()
                .map(ReplayRepository.ReplayRow::replayId)
                .toList());

        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select pg_typeof(status)::text, "
                                + "pg_typeof(storage_backend)::text, "
                                + "pg_typeof(metadata)::text, "
                                + "(select pg_typeof(operation)::text from metadata_revision "
                                + "where replay_id = r.id and revision = 1) "
                                + "from replay r where id = ?")) {
            statement.setObject(1, replayId.value());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("replay_status", result.getString(1));
                assertEquals("replay_storage_backend", result.getString(2));
                assertEquals("jsonb", result.getString(3));
                assertEquals("replay_metadata_revision_operation", result.getString(4));
            }
        }
    }

    private static ReplayMetadataKey<String> stringKey(String name) {
        TypeAdapter<String> codec = new Gson().getAdapter(String.class).nullSafe();
        return ReplayMetadataKey.of(
                Key.key(name),
                TypeToken.get(String.class),
                codec,
                QueryCapabilities.of(
                        QueryCapabilities.Operation.EQUALITY,
                        new QueryCapabilities.Operation[]{
                                QueryCapabilities.Operation.EXISTENCE}));
    }
}
