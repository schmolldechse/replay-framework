package dev.voldechse.replayframework.database.postgresql.repository;

import com.google.gson.Gson;
import com.google.inject.Inject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.database.postgresql.HibernateSessionFactory;
import dev.voldechse.replayframework.database.postgresql.entity.ReplayEntity;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.database.postgresql.query.PostgresReplayQueryCompiler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.hibernate.LockMode;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

/** Hibernate implementation of the internal replay catalog port. */
public final class HibernateReplayRepository implements ReplayRepository {
    private final HibernateSessionFactory sessionFactory;
    private final PostgresReplayQueryCompiler queryCompiler;
    private final Gson gson;

    /** Creates a repository backed by the shared transaction boundary. */
    @Inject
    public HibernateReplayRepository(
            HibernateSessionFactory sessionFactory,
            PostgresReplayQueryCompiler queryCompiler,
            Gson gson) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.queryCompiler = Objects.requireNonNull(queryCompiler, "queryCompiler");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    @Override
    public CompletionStage<ReplayRow> create(ReplayCreate command) {
        Objects.requireNonNull(command, "command");
        return sessionFactory.executeAsync(session -> {
            ReplayEntity entity = ReplayEntity.newInstance();
            entity.setId(command.replayId().value());
            entity.setTitle(command.title());
            entity.setDescription(command.description());
            entity.setStatus(RecordingStatus.INITIALIZING);
            entity.setCompletionReason(null);
            entity.setFailureCode(null);
            entity.setFailureDescription(null);
            entity.setAdapterId(command.adapterId());
            entity.setProtocolVersion(command.protocolVersion());
            entity.setFormatRevision(command.formatRevision());
            entity.setStorageBackend(command.storageBackend());
            entity.setStorageKey(command.storageKey());
            entity.setDurationNanos(0L);
            entity.setTotalBytes(0L);
            entity.setPacketCount(0L);
            entity.setSegmentCount(0L);
            entity.setCheckpointCount(0L);
            entity.setCreatedAt(command.createdAt());
            entity.setStartedAt(null);
            entity.setCompletedAt(null);
            entity.setMetadata(command.metadata());
            entity.setMetadataRevision(0L);
            session.persist(entity);
            session.flush();
            return toRow(entity);
        });
    }

    @Override
    public CompletionStage<Optional<ReplayRow>> find(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return sessionFactory.executeAsync(session -> Optional.ofNullable(
                session.find(ReplayEntity.class, replayId.value())).map(HibernateReplayRepository::toRow));
    }

    @Override
    public CompletionStage<ReplayPage<ReplayRow>> page(ReplayQuery query) {
        Objects.requireNonNull(query, "query");
        PostgresReplayQueryCompiler.CompiledQuery compiled;
        try {
            compiled = queryCompiler.compile(query);
        } catch (RuntimeException exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(exception);
        }

        return sessionFactory.executeAsync(session -> {
            String sql = "select r.id from replay r where 1 = 1"
                    + compiled.whereClause()
                    + " order by "
                    + compiled.orderByClause()
                    + " limit :page_limit";
            NativeQuery<java.util.UUID> statement = session.createNativeQuery(sql, java.util.UUID.class);
            bindParameters(statement, compiled.parameters());
            statement.setParameter("page_limit", (long) compiled.limit() + 1L);

            List<java.util.UUID> ids = statement
                    .getResultList();
            boolean hasNext = ids.size() > compiled.limit();
            if (hasNext) {
                ids = new ArrayList<>(ids.subList(0, compiled.limit()));
            }
            List<ReplayRow> rows = ids.stream()
                    .map(id -> session.find(ReplayEntity.class, id))
                    .map(entity -> {
                        if (entity == null) {
                            throw new IllegalStateException(
                                    "replay disappeared while hydrating a catalog page");
                        }
                        return toRow(entity);
                    })
                    .toList();
            Optional<String> nextCursor = hasNext && !rows.isEmpty()
                    ? Optional.of(queryCompiler.encodeCursor(query, rows.get(rows.size() - 1)))
                    : Optional.empty();
            return new ReplayPage<>(rows, nextCursor);
        });
    }

    @Override
    public CompletionStage<ReplayRow> transition(ReplayTransition command) {
        Objects.requireNonNull(command, "command");
        return sessionFactory.executeAsync(session -> {
            ReplayEntity entity = session.find(
                    ReplayEntity.class,
                    command.replayId().value(),
                    LockMode.PESSIMISTIC_WRITE);
            if (entity == null) {
                throw new ReplayNotFoundException(command.replayId());
            }
            if (entity.getStatus() != command.expectedStatus()) {
                throw new StatusTransitionConflictException(
                        command.replayId(), command.expectedStatus());
            }

            Instant startedAt = command.startedAt().orElse(entity.getStartedAt());
            Instant completedAt = command.completedAt().orElse(entity.getCompletedAt());
            validateTimeOrder(entity, startedAt, completedAt);

            command.startedAt().ifPresent(entity::setStartedAt);
            command.completedAt().ifPresent(entity::setCompletedAt);
            command.metrics().ifPresent(metrics -> applyMetrics(entity, metrics));

            switch (command.nextStatus()) {
                case AVAILABLE -> {
                    entity.setCompletionReason(command.completionReason().orElseThrow());
                    entity.setFailureCode(null);
                    entity.setFailureDescription(null);
                }
                case FAILED -> {
                    FailureDetails failure = command.failure().orElseThrow();
                    entity.setCompletionReason(null);
                    entity.setFailureCode(failure.code());
                    entity.setFailureDescription(failure.description());
                    entity.setCompletedAt(failure.completedAt());
                    validateTimeOrder(entity, entity.getStartedAt(), failure.completedAt());
                }
                default -> {
                    // Intermediate transitions preserve already persisted completion fields.
                }
            }
            entity.setStatus(command.nextStatus());
            session.flush();
            return toRow(entity);
        });
    }

    @Override
    public CompletionStage<Void> delete(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return sessionFactory.executeAsync(session -> {
            ReplayEntity entity = session.find(
                    ReplayEntity.class,
                    replayId.value(),
                    LockMode.PESSIMISTIC_WRITE);
            if (entity == null) {
                throw new ReplayNotFoundException(replayId);
            }
            if (entity.getStatus() != RecordingStatus.DELETING) {
                throw new StatusTransitionConflictException(replayId, RecordingStatus.DELETING);
            }
            session.remove(entity);
            session.flush();
            return (Void) null;
        });
    }

    private static void applyMetrics(ReplayEntity entity, ReplayMetrics metrics) {
        entity.setDurationNanos(metrics.durationNanos());
        entity.setTotalBytes(metrics.totalBytes());
        entity.setPacketCount(metrics.packetCount());
        entity.setSegmentCount(metrics.segmentCount());
        entity.setCheckpointCount(metrics.checkpointCount());
    }

    private static void validateTimeOrder(ReplayEntity entity, Instant startedAt, Instant completedAt) {
        if (startedAt != null && startedAt.isBefore(entity.getCreatedAt())) {
            throw new IllegalArgumentException("startedAt must not precede createdAt");
        }
        if (completedAt != null && completedAt.isBefore(entity.getCreatedAt())) {
            throw new IllegalArgumentException("completedAt must not precede createdAt");
        }
        if (startedAt != null && completedAt != null && completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
    }

    private static ReplayRow toRow(ReplayEntity entity) {
        return new ReplayRow(
                new ReplayId(entity.getId()),
                entity.getTitle(),
                entity.getDescription(),
                entity.getStatus(),
                Optional.ofNullable(entity.getCompletionReason()),
                Optional.ofNullable(entity.getFailureCode()),
                Optional.ofNullable(entity.getFailureDescription()),
                entity.getAdapterId(),
                entity.getProtocolVersion(),
                entity.getFormatRevision(),
                entity.getStorageBackend(),
                entity.getStorageKey(),
                entity.getDurationNanos(),
                entity.getTotalBytes(),
                entity.getPacketCount(),
                entity.getSegmentCount(),
                entity.getCheckpointCount(),
                entity.getCreatedAt(),
                Optional.ofNullable(entity.getStartedAt()),
                Optional.ofNullable(entity.getCompletedAt()),
                entity.getMetadata(),
                entity.getMetadataRevision(),
                entity.getEntityVersion());
    }

    private void bindParameters(
            Query<?> statement,
            List<PostgresReplayQueryCompiler.Parameter> parameters) {
        for (PostgresReplayQueryCompiler.Parameter parameter : parameters) {
            Object value = parameter.kind() == PostgresReplayQueryCompiler.ParameterKind.JSONB
                    ? gson.toJson(parameter.value())
                    : parameter.value();
            statement.setParameter(parameter.name(), value);
        }
    }
}
