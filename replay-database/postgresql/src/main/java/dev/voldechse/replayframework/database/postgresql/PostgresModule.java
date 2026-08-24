package dev.voldechse.replayframework.database.postgresql;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.voldechse.replayframework.core.metadata.MetadataRegistry;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import dev.voldechse.replayframework.core.port.MetadataRepository;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.database.postgresql.repository.HibernateLeaseRepository;
import dev.voldechse.replayframework.database.postgresql.repository.HibernateMetadataRepository;
import dev.voldechse.replayframework.database.postgresql.repository.HibernateReplayRepository;
import dev.voldechse.replayframework.database.postgresql.query.PostgresReplayQueryCompiler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Guice module composing the internal PostgreSQL persistence graph. */
public final class PostgresModule extends AbstractModule {
    /** Named binding used by the Hibernate execution boundary. */
    public static final String DATABASE_EXECUTOR = "replay-database";

    private final Configuration configuration;
    private final Gson gson;

    /**
     * Creates the module with runtime-owned configuration and Gson registry.
     *
     * @param configuration validated PostgreSQL settings
     * @param gson central Gson instance used for JSONB mapping
     */
    public PostgresModule(Configuration configuration, Gson gson) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    protected void configure() {
        bind(Configuration.class).toInstance(configuration);
        bind(Gson.class).toInstance(gson);
        bind(MetadataRegistry.class).asEagerSingleton();
        bind(PostgresReplayQueryCompiler.class).in(Singleton.class);
        bind(HibernateSessionFactory.class).asEagerSingleton();
        bind(ReplayRepository.class).to(HibernateReplayRepository.class).in(Singleton.class);
        bind(MetadataRepository.class).to(HibernateMetadataRepository.class).in(Singleton.class);
        bind(LeaseRepository.class).to(HibernateLeaseRepository.class).in(Singleton.class);
    }

    /** Creates the one Hikari pool used by the Hibernate SessionFactory. */
    @Provides
    @Singleton
    HikariDataSource provideDataSource(Configuration settings) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(settings.jdbcUrl());
        hikari.setUsername(settings.username());
        hikari.setPassword(settings.password());
        hikari.setPoolName("replay-postgres");
        hikari.setMinimumIdle(settings.minimumIdle());
        hikari.setMaximumPoolSize(settings.maximumPoolSize());
        hikari.setConnectionTimeout(settings.connectionTimeout().toMillis());
        hikari.setValidationTimeout(settings.validationTimeout().toMillis());
        hikari.setIdleTimeout(settings.idleTimeout().toMillis());
        hikari.setMaxLifetime(settings.maxLifetime().toMillis());
        return new HikariDataSource(hikari);
    }

    /** Creates the bounded executor that owns every Hibernate operation. */
    @Provides
    @Singleton
    @Named(DATABASE_EXECUTOR)
    ExecutorService provideDatabaseExecutor(Configuration settings) {
        ThreadFactory threads = Thread.ofPlatform().name("replay-db-", 0).factory();
        return new ThreadPoolExecutor(
                settings.databaseParallelism(),
                settings.databaseParallelism(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.databaseQueueCapacity()),
                threads,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Immutable internal configuration for the PostgreSQL module. */
    public record Configuration(
            String jdbcUrl,
            String username,
            String password,
            int minimumIdle,
            int maximumPoolSize,
            Duration connectionTimeout,
            Duration validationTimeout,
            Duration idleTimeout,
            Duration maxLifetime,
            int databaseParallelism,
            int databaseQueueCapacity) {

        /** Validates configuration before Hikari or an executor is created. */
        public Configuration {
            requireNonBlank(jdbcUrl, "jdbcUrl");
            if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException("jdbcUrl must use the PostgreSQL JDBC scheme");
            }
            requireNonBlank(username, "username");
            requireNonBlank(password, "password");
            if (minimumIdle < 0) {
                throw new IllegalArgumentException("minimumIdle must not be negative");
            }
            if (maximumPoolSize <= 0) {
                throw new IllegalArgumentException("maximumPoolSize must be positive");
            }
            if (minimumIdle > maximumPoolSize) {
                throw new IllegalArgumentException("minimumIdle must not exceed maximumPoolSize");
            }
            requirePositive(connectionTimeout, "connectionTimeout");
            requirePositive(validationTimeout, "validationTimeout");
            requirePositive(idleTimeout, "idleTimeout");
            requirePositive(maxLifetime, "maxLifetime");
            if (databaseParallelism <= 0) {
                throw new IllegalArgumentException("databaseParallelism must be positive");
            }
            if (databaseQueueCapacity <= 0) {
                throw new IllegalArgumentException("databaseQueueCapacity must be positive");
            }
        }

        /** Prevents credentials from appearing in diagnostics produced for this configuration. */
        @Override
        public String toString() {
            return "Configuration["
                    + "jdbcUrl=" + jdbcUrl
                    + ", username=" + username
                    + ", password=<redacted>"
                    + ", minimumIdle=" + minimumIdle
                    + ", maximumPoolSize=" + maximumPoolSize
                    + ", connectionTimeout=" + connectionTimeout
                    + ", validationTimeout=" + validationTimeout
                    + ", idleTimeout=" + idleTimeout
                    + ", maxLifetime=" + maxLifetime
                    + ", databaseParallelism=" + databaseParallelism
                    + ", databaseQueueCapacity=" + databaseQueueCapacity
                    + ']';
        }

        private static void requireNonBlank(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }

        private static void requirePositive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
