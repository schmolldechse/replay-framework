package dev.voldechse.replayframework.database.postgresql.entity;

import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.recording.ReplayCompletionReason;
import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Hibernate persistence representation of the replay catalog row.
 *
 * <p>This type is deliberately not part of the public API. Repository code
 * translates its scalar UUID and JSON values into API read models while
 * transaction code owns lifecycle transitions.</p>
 */
@Entity
@Table(name = "replay")
public class ReplayEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    /** Native PostgreSQL enum; do not replace with a text column. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "replay_status")
    private RecordingStatus status;

    /** Native PostgreSQL enum used only for clean AVAILABLE finalization. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "completion_reason", columnDefinition = "replay_completion_reason")
    private ReplayCompletionReason completionReason;

    /** Native PostgreSQL enum used only for FAILED diagnostics. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "failure_code", columnDefinition = "replay_failure_code")
    private ReplayFailureCode failureCode;

    @Column(name = "failure_description")
    private String failureDescription;

    @Column(name = "adapter_id", nullable = false)
    private String adapterId;

    @Column(name = "protocol_version", nullable = false)
    private int protocolVersion;

    @Column(name = "format_revision", nullable = false)
    private int formatRevision;

    /** Native PostgreSQL enum identifying the selected artifact backend. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "storage_backend", nullable = false, columnDefinition = "replay_storage_backend")
    private ReplayStorageBackend storageBackend;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "duration_nanos", nullable = false)
    private long durationNanos;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "packet_count", nullable = false)
    private long packetCount;

    @Column(name = "segment_count", nullable = false)
    private long segmentCount;

    @Column(name = "checkpoint_count", nullable = false)
    private long checkpointCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Gson JSON object containing the current custom metadata values. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private JsonObject metadata;

    @Column(name = "metadata_revision", nullable = false)
    private long metadataRevision;

    /** Hibernate-managed optimistic-lock value; application code must not increment it. */
    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    protected ReplayEntity() {
        // Required by Hibernate; repositories provide all values before persist.
    }

    /** Creates an empty entity for the internal repository boundary. */
    public static ReplayEntity newInstance() {
        return new ReplayEntity();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RecordingStatus getStatus() {
        return status;
    }

    public void setStatus(RecordingStatus status) {
        this.status = status;
    }

    public ReplayCompletionReason getCompletionReason() {
        return completionReason;
    }

    public void setCompletionReason(ReplayCompletionReason completionReason) {
        this.completionReason = completionReason;
    }

    public ReplayFailureCode getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(ReplayFailureCode failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureDescription() {
        return failureDescription;
    }

    public void setFailureDescription(String failureDescription) {
        this.failureDescription = failureDescription;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public void setAdapterId(String adapterId) {
        this.adapterId = adapterId;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public int getFormatRevision() {
        return formatRevision;
    }

    public void setFormatRevision(int formatRevision) {
        this.formatRevision = formatRevision;
    }

    public ReplayStorageBackend getStorageBackend() {
        return storageBackend;
    }

    public void setStorageBackend(ReplayStorageBackend storageBackend) {
        this.storageBackend = storageBackend;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    public void setDurationNanos(long durationNanos) {
        this.durationNanos = durationNanos;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public long getPacketCount() {
        return packetCount;
    }

    public void setPacketCount(long packetCount) {
        this.packetCount = packetCount;
    }

    public long getSegmentCount() {
        return segmentCount;
    }

    public void setSegmentCount(long segmentCount) {
        this.segmentCount = segmentCount;
    }

    public long getCheckpointCount() {
        return checkpointCount;
    }

    public void setCheckpointCount(long checkpointCount) {
        this.checkpointCount = checkpointCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public JsonObject getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonObject metadata) {
        this.metadata = metadata;
    }

    public long getMetadataRevision() {
        return metadataRevision;
    }

    public void setMetadataRevision(long metadataRevision) {
        this.metadataRevision = metadataRevision;
    }

    public long getEntityVersion() {
        return entityVersion;
    }
}
