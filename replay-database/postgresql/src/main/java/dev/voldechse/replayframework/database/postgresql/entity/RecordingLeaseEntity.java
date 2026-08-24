package dev.voldechse.replayframework.database.postgresql.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Versioned lease row protecting one active recording instance.
 *
 * <p>Lease lifecycle is represented by row existence and timestamps. There
 * is intentionally no additional persisted lease-status enum.</p>
 */
@Entity
@Table(name = "recording_lease")
public class RecordingLeaseEntity {

    @Id
    @Column(name = "replay_id", nullable = false)
    private UUID replayId;

    @Column(name = "runtime_instance_id", nullable = false)
    private UUID runtimeInstanceId;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Hibernate-managed optimistic-lock value for concurrent heartbeats. */
    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    protected RecordingLeaseEntity() {
        // Required by Hibernate.
    }

    /** Creates an empty lease entity for the internal repository boundary. */
    public static RecordingLeaseEntity newInstance() {
        return new RecordingLeaseEntity();
    }

    public UUID getReplayId() {
        return replayId;
    }

    public void setReplayId(UUID replayId) {
        this.replayId = replayId;
    }

    public UUID getRuntimeInstanceId() {
        return runtimeInstanceId;
    }

    public void setRuntimeInstanceId(UUID runtimeInstanceId) {
        this.runtimeInstanceId = runtimeInstanceId;
    }

    public Instant getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(Instant acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getEntityVersion() {
        return entityVersion;
    }
}
