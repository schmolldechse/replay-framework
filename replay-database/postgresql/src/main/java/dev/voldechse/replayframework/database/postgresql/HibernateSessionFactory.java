package dev.voldechse.replayframework.database.postgresql;

import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.zaxxer.hikari.HikariDataSource;
import dev.voldechse.replayframework.database.postgresql.entity.MetadataRevisionEntity;
import dev.voldechse.replayframework.database.postgresql.entity.RecordingLeaseEntity;
import dev.voldechse.replayframework.database.postgresql.entity.ReplayEntity;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;

/**
 * Owns the Flyway-before-Hibernate bootstrap and the per-operation Session boundary.
 *
 * <p>The class is internal. Repository callers must use executeAsync so database work never
 * runs on the Paper thread.</p>
 */
public final class HibernateSessionFactory implements AutoCloseable {
    private static final long CLOSE_TIMEOUT_SECONDS = 30L;

    private final HikariDataSource dataSource;
    private final ExecutorService databaseExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SessionFactory sessionFactory;

    /** Bootstraps Flyway and Hibernate before the instance becomes injectable. */
    @Inject
    public HibernateSessionFactory(
            PostgresModule.Configuration configuration,
            HikariDataSource dataSource,
            Gson gson,
            @Named(PostgresModule.DATABASE_EXECUTOR) ExecutorService databaseExecutor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor, "databaseExecutor");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(gson, "gson");

        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();
            this.sessionFactory = buildSessionFactory(dataSource, gson);
        } catch (RuntimeException exception) {
            databaseExecutor.shutdownNow();
            dataSource.close();
            throw exception;
        }
    }

    /**
     * Executes one unit of work in a new Hibernate session and transaction.
     *
     * @param work transaction callback
     * @param <T> result type
     * @return callback result after commit
     */
    public <T> T inTransaction(Function<Session, T> work) {
        Objects.requireNonNull(work, "work");
        if (closed.get()) {
            throw new IllegalStateException("HibernateSessionFactory is closed");
        }
        try (Session session = sessionFactory.openSession()) {
            Transaction transaction = session.beginTransaction();
            try {
                T result = work.apply(session);
                session.flush();
                transaction.commit();
                return result;
            } catch (RuntimeException | Error exception) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw exception;
            }
        }
    }

    /** Dispatches a transaction to the dedicated bounded database executor. */
    public <T> CompletionStage<T> executeAsync(Function<Session, T> work) {
        Objects.requireNonNull(work, "work");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("HibernateSessionFactory is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> inTransaction(work), databaseExecutor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Returns whether the internal resource owner has been closed. */
    public boolean isClosed() {
        return closed.get();
    }

    /** Closes executor, Hibernate and Hikari resources in dependency order. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                databaseExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            databaseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            sessionFactory.close();
            dataSource.close();
        }
    }

    private static SessionFactory buildSessionFactory(HikariDataSource dataSource, Gson gson) {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DATASOURCE, dataSource)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "validate")
                .applySetting(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .applySetting(AvailableSettings.SHOW_SQL, false)
                .applySetting(AvailableSettings.FORMAT_SQL, false)
                .applySetting(AvailableSettings.JSON_FORMAT_MAPPER, new GsonFormatMapper(gson))
                .build();
        try {
            Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClasses(
                            ReplayEntity.class,
                            MetadataRevisionEntity.class,
                            RecordingLeaseEntity.class)
                    .buildMetadata(registry);
            return metadata.buildSessionFactory();
        } catch (RuntimeException | Error exception) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw exception;
        }
    }

    /** Minimal Hibernate FormatMapper backed solely by the framework Gson instance. */
    private static final class GsonFormatMapper implements FormatMapper {
        private final Gson gson;

        private GsonFormatMapper(Gson gson) {
            this.gson = gson;
        }

        @Override
        public <T> T fromString(
                CharSequence value,
                JavaType<T> javaType,
                WrapperOptions options) {
            if (value == null) {
                return null;
            }
            return gson.fromJson(value.toString(), javaType.getJavaType());
        }

        @Override
        public <T> String toString(T value, JavaType<T> javaType, WrapperOptions options) {
            if (value == null) {
                return null;
            }
            return gson.toJson(value, javaType.getJavaType());
        }

    }
}
