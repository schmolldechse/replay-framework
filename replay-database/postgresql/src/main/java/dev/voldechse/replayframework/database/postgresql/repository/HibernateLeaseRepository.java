package dev.voldechse.replayframework.database.postgresql.repository;

import com.google.inject.Inject;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.database.postgresql.HibernateSessionFactory;
import dev.voldechse.replayframework.database.postgresql.entity.RecordingLeaseEntity;
import dev.voldechse.replayframework.database.postgresql.entity.ReplayEntity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.hibernate.LockMode;
import org.hibernate.query.Query;

/** Hibernate implementation of the exclusive recording lease port. */
public final class HibernateLeaseRepository implements LeaseRepository {
    private final HibernateSessionFactory sessionFactory;

    /** Creates a repository backed by the shared transaction boundary. */
    @Inject
    public HibernateLeaseRepository(HibernateSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    @Override
    public CompletionStage<LeaseRow> acquire(LeaseAcquire command) {
        Objects.requireNonNull(command, "command");
        return sessionFactory.executeAsync(session -> {
            ReplayEntity replay = session.find(
                    ReplayEntity.class,
                    command.replayId().value(),
                    LockMode.PESSIMISTIC_WRITE);
            if (replay == null) {
                throw new ReplayRepository.ReplayNotFoundException(command.replayId());
            }
            if (!isActiveRecordingStatus(replay.getStatus())) {
                throw new LeaseNotActiveException(command.replayId());
            }

            RecordingLeaseEntity existing = session.find(
                    RecordingLeaseEntity.class,
                    command.replayId().value(),
                    LockMode.PESSIMISTIC_WRITE);
            if (existing != null) {
                if (existing.getExpiresAt().isAfter(command.acquiredAt())) {
                    throw new LeaseAlreadyHeldException(command.replayId());
                }
                session.remove(existing);
                session.flush();
            }

            RecordingLeaseEntity lease = RecordingLeaseEntity.newInstance();
            lease.setReplayId(command.replayId().value());
            lease.setRuntimeInstanceId(command.runtimeInstanceId());
            lease.setAcquiredAt(command.acquiredAt());
            lease.setHeartbeatAt(command.heartbeatAt());
            lease.setExpiresAt(command.expiresAt());
            session.persist(lease);
            session.flush();
            return toRow(lease);
        });
    }

    @Override
    public CompletionStage<Boolean> renew(LeaseRenew command) {
        Objects.requireNonNull(command, "command");
        return sessionFactory.executeAsync(session -> {
            RecordingLeaseEntity lease = session.find(
                    RecordingLeaseEntity.class,
                    command.replayId().value(),
                    LockMode.PESSIMISTIC_WRITE);
            if (lease == null
                    || !lease.getRuntimeInstanceId().equals(command.runtimeInstanceId())
                    || !lease.getExpiresAt().isAfter(command.heartbeatAt())) {
                return false;
            }
            lease.setHeartbeatAt(command.heartbeatAt());
            lease.setExpiresAt(command.expiresAt());
            session.flush();
            return true;
        });
    }

    @Override
    public CompletionStage<Boolean> release(ReplayId replayId, UUID runtimeInstanceId) {
        Objects.requireNonNull(replayId, "replayId");
        Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        return sessionFactory.executeAsync(session -> {
            RecordingLeaseEntity lease = session.find(
                    RecordingLeaseEntity.class,
                    replayId.value(),
                    LockMode.PESSIMISTIC_WRITE);
            if (lease == null || !lease.getRuntimeInstanceId().equals(runtimeInstanceId)) {
                return false;
            }
            session.remove(lease);
            session.flush();
            return true;
        });
    }

    @Override
    public CompletionStage<List<LeaseRow>> findExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return sessionFactory.executeAsync(session -> {
            Query<RecordingLeaseEntity> query = session.createQuery(
                    "from RecordingLeaseEntity l where l.expiresAt <= :now order by l.expiresAt asc",
                    RecordingLeaseEntity.class);
            query.setParameter("now", now);
            return query.getResultList().stream().map(HibernateLeaseRepository::toRow).toList();
        });
    }

    private static boolean isActiveRecordingStatus(RecordingStatus status) {
        return status == RecordingStatus.INITIALIZING
                || status == RecordingStatus.RECORDING
                || status == RecordingStatus.FINALIZING;
    }

    private static LeaseRow toRow(RecordingLeaseEntity entity) {
        return new LeaseRow(
                new ReplayId(entity.getReplayId()),
                entity.getRuntimeInstanceId(),
                entity.getAcquiredAt(),
                entity.getHeartbeatAt(),
                entity.getExpiresAt(),
                entity.getEntityVersion());
    }
}
