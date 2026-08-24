package dev.voldechse.replayframework.database.postgresql.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.core.port.MetadataRepository;
import dev.voldechse.replayframework.database.postgresql.HibernateSessionFactory;
import dev.voldechse.replayframework.database.postgresql.entity.MetadataRevisionEntity;
import dev.voldechse.replayframework.database.postgresql.entity.ReplayEntity;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.hibernate.LockMode;
import org.hibernate.query.Query;

/** Hibernate implementation of the internal metadata persistence port. */
public final class HibernateMetadataRepository implements MetadataRepository {
    private final HibernateSessionFactory sessionFactory;

    /** Creates a repository backed by the shared transaction boundary. */
    @Inject
    public HibernateMetadataRepository(HibernateSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    @Override
    public CompletionStage<Optional<MetadataSnapshot>> current(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return sessionFactory.executeAsync(session -> Optional.ofNullable(
                session.find(ReplayEntity.class, replayId.value())).map(entity -> toCurrent(entity, replayId)));
    }

    @Override
    public CompletionStage<List<MetadataRevisionRow>> history(ReplayId replayId) {
        Objects.requireNonNull(replayId, "replayId");
        return sessionFactory.executeAsync(session -> {
            if (session.find(ReplayEntity.class, replayId.value()) == null) {
                throw new MetadataNotFoundException(replayId);
            }
            Query<MetadataRevisionEntity> query = session.createQuery(
                    "from MetadataRevisionEntity m where m.replayId = :replayId order by m.revision asc",
                    MetadataRevisionEntity.class);
            query.setParameter("replayId", replayId.value());
            return query.getResultList().stream().map(entity -> toRevisionRow(entity, replayId)).toList();
        });
    }

    @Override
    public CompletionStage<MetadataSnapshot> apply(MetadataWrite command) {
        Objects.requireNonNull(command, "command");
        return sessionFactory.executeAsync(session -> {
            ReplayEntity replay = session.find(
                    ReplayEntity.class,
                    command.replayId().value(),
                    LockMode.PESSIMISTIC_WRITE);
            if (replay == null) {
                throw new MetadataNotFoundException(command.replayId());
            }
            if (replay.getMetadataRevision() != command.expectedRevision()) {
                throw new MetadataRevisionConflictException(
                        command.replayId(), command.expectedRevision());
            }

            MetadataSnapshot next = command.nextSnapshot();
            JsonObject values = next.values();
            replay.setTitle(next.title());
            replay.setDescription(next.description());
            replay.setMetadata(values);
            replay.setMetadataRevision(next.revision());

            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("title", next.title());
            snapshot.addProperty("description", next.description());
            snapshot.add("values", values.deepCopy());

            MetadataRevisionEntity revision = MetadataRevisionEntity.newInstance();
            revision.setId(UUID.randomUUID());
            revision.setReplayId(command.replayId().value());
            revision.setRevision(next.revision());
            revision.setCreatedAt(command.committedAt());
            revision.setOperation(command.operation());
            revision.setSnapshot(snapshot);
            session.persist(revision);
            session.flush();
            return new MetadataSnapshot(
                    next.replayId(), next.title(), next.description(), next.revision(), values);
        });
    }

    private static MetadataSnapshot toCurrent(ReplayEntity entity, ReplayId replayId) {
        return new MetadataSnapshot(
                replayId,
                entity.getTitle(),
                entity.getDescription(),
                entity.getMetadataRevision(),
                entity.getMetadata());
    }

    private static MetadataRevisionRow toRevisionRow(
            MetadataRevisionEntity entity, ReplayId replayId) {
        JsonObject snapshot = entity.getSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException(
                    "metadata revision contains an invalid canonical snapshot: " + entity.getRevision());
        }
        JsonElement title = snapshot.get("title");
        JsonElement description = snapshot.get("description");
        JsonElement values = snapshot.get("values");
        if (title == null
                || description == null
                || !title.isJsonPrimitive()
                || !title.getAsJsonPrimitive().isString()
                || !description.isJsonPrimitive()
                || !description.getAsJsonPrimitive().isString()
                || values == null
                || !values.isJsonObject()) {
            throw new IllegalStateException(
                    "metadata revision contains an invalid canonical snapshot: " + entity.getRevision());
        }
        MetadataSnapshot metadata = new MetadataSnapshot(
                replayId,
                title.getAsString(),
                description.getAsString(),
                entity.getRevision(),
                values.getAsJsonObject());
        return new MetadataRevisionRow(
                entity.getRevision(), entity.getCreatedAt(), entity.getOperation(), metadata);
    }
}
