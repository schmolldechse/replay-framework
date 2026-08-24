package dev.voldechse.replayframework.database.postgresql.entity;

import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.api.metadata.MetadataRevisionOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable persistence row for one metadata mutation snapshot.
 *
 * <p>Revision zero remains on {@code ReplayEntity}; rows represented here
 * start at revision one and are inserted, never updated, by Task 13.</p>
 */
@Entity
@Table(
        name = "metadata_revision",
        uniqueConstraints = @UniqueConstraint(
                name = "metadata_revision_unique_number",
                columnNames = {"replay_id", "revision"}))
public class MetadataRevisionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "replay_id", nullable = false)
    private UUID replayId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Native PostgreSQL enum describing the persisted mutation operation. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(
            name = "operation",
            nullable = false,
            columnDefinition = "replay_metadata_revision_operation")
    private MetadataRevisionOperation operation;

    /** Full Gson snapshot containing title, description and custom values. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonObject snapshot;

    protected MetadataRevisionEntity() {
        // Required by Hibernate; repositories create append-only rows explicitly.
    }

    /** Creates an empty append-only entity for the internal repository boundary. */
    public static MetadataRevisionEntity newInstance() {
        return new MetadataRevisionEntity();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getReplayId() {
        return replayId;
    }

    public void setReplayId(UUID replayId) {
        this.replayId = replayId;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public MetadataRevisionOperation getOperation() {
        return operation;
    }

    public void setOperation(MetadataRevisionOperation operation) {
        this.operation = operation;
    }

    public JsonObject getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(JsonObject snapshot) {
        this.snapshot = snapshot;
    }
}
