package dev.voldechse.replayframework.core.playback;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates in-memory playback ownership for one runtime instance. */
final class PlaybackCoordinator {

    private final ConcurrentMap<PlaybackSessionId, PlaybackSession> bySessionId =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PlaybackSessionId> byViewerId =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    /** Reserves one viewer for a session before asynchronous opening begins. */
    void reserve(PlaybackSessionId sessionId, UUID viewerId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(viewerId, "viewerId");
        PlaybackSessionId existing;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("playback coordinator is shut down");
            }
            existing = byViewerId.putIfAbsent(viewerId, sessionId);
        }
        if (existing != null) {
            throw new IllegalStateException(
                    "viewer already has an active playback session: " + viewerId
                            + " (" + existing + ")");
        }
    }

    /** Registers a session only after its viewer reservation has been established. */
    void register(PlaybackSession session) {
        Objects.requireNonNull(session, "session");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("playback coordinator is shut down");
            }
            PlaybackSessionId reserved = byViewerId.get(session.viewerId());
            if (!session.id().equals(reserved)) {
                throw new IllegalStateException("playback session has no matching viewer reservation");
            }
            PlaybackSession existing = bySessionId.putIfAbsent(session.id(), session);
            if (existing != null) {
                throw new IllegalStateException("playback session is already registered: " + session.id());
            }
        }
    }

    /** Returns one managed, non-terminal playback session without performing I/O. */
    Optional<PlaybackSession> active(PlaybackSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        PlaybackSession session = bySessionId.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        PlaybackStatus status = session.snapshot().status();
        if (status == PlaybackStatus.CLOSED || status == PlaybackStatus.FAILED) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** Removes only the supplied session and never a newer session for the same viewer. */
    void release(PlaybackSessionId sessionId, UUID viewerId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(viewerId, "viewerId");
        byViewerId.remove(viewerId, sessionId);
        bySessionId.remove(sessionId);
    }

    /** Closes every currently registered viewer session during runtime shutdown. */
    CompletionStage<Void> closeAll() {
        java.util.List<PlaybackSession> sessions;
        synchronized (lifecycleLock) {
            closed.set(true);
            sessions = java.util.List.copyOf(bySessionId.values());
        }
        java.util.List<CompletionStage<?>> closes = new java.util.ArrayList<>();
        for (PlaybackSession session : sessions) {
            try {
                closes.add(session.close().handle((ignored, failure) -> null));
            } catch (RuntimeException ignored) {
                // Individual session cleanup must not prevent remaining viewers from closing.
            }
        }
        bySessionId.clear();
        byViewerId.clear();
        if (closes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] stages = closes.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(stages);
    }
}
