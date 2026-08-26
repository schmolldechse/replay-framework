package dev.voldechse.replayframework.example.viewer;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;

/**
 * Owns the in-memory lifecycle entries of the Example viewers.
 *
 * <p>The registry is deliberately scoped to the Example plugin. Framework
 * playback sessions remain owned by the public playback service.</p>
 */
final class ViewerSessionRegistry {

    enum State {
        PACK_PENDING,
        PREPARING,
        PLAYBACK_OPENING,
        ACTIVE,
        RESTORING
    }

    static final class ViewerSession {
        private final UUID viewerId;
        private final Player player;
        private final ReplayId replayId;
        private final AtomicReference<State> state =
                new AtomicReference<>(State.PACK_PENDING);
        private final AtomicBoolean restoreStarted = new AtomicBoolean();
        private final CompletableFuture<Void> restoreStage = new CompletableFuture<>();
        private final CompletableFuture<PlaybackSession> openResult = new CompletableFuture<>();
        private volatile StoredPlayerState storedPlayerState;
        private volatile CompletionStage<PlaybackSession> playbackOpen;
        private volatile PlaybackSession playback;

        private ViewerSession(Player player, ReplayId replayId) {
            this.viewerId = player.getUniqueId();
            this.player = player;
            this.replayId = replayId;
        }

        UUID viewerId() {
            return viewerId;
        }

        Player player() {
            return player;
        }

        ReplayId replayId() {
            return replayId;
        }

        State state() {
            return state.get();
        }

        StoredPlayerState storedPlayerState() {
            return storedPlayerState;
        }

        CompletionStage<PlaybackSession> playbackOpen() {
            return playbackOpen;
        }

        PlaybackSession playback() {
            return playback;
        }

        CompletionStage<Void> restoreStage() {
            return restoreStage;
        }

        CompletableFuture<PlaybackSession> openResult() {
            return openResult;
        }

        boolean markRestoreStarted() {
            return restoreStarted.compareAndSet(false, true);
        }

        boolean compareAndSet(State expected, State next) {
            return state.compareAndSet(expected, next);
        }

        void storedPlayerState(StoredPlayerState value) {
            if (storedPlayerState != null) {
                throw new IllegalStateException("Viewer player state is already stored");
            }
            storedPlayerState = Objects.requireNonNull(value, "value");
        }

        void playbackOpen(CompletionStage<PlaybackSession> value) {
            if (playbackOpen != null) {
                throw new IllegalStateException("Playback open stage is already attached");
            }
            playbackOpen = Objects.requireNonNull(value, "value");
        }

        void playback(PlaybackSession value) {
            if (playback != null) {
                throw new IllegalStateException("Playback session is already attached");
            }
            playback = Objects.requireNonNull(value, "value");
        }

        void completeRestore() {
            restoreStage.complete(null);
        }
    }

    private final ConcurrentMap<UUID, ViewerSession> entries = new ConcurrentHashMap<>();

    ViewerSession reserve(Player player, ReplayId replayId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(replayId, "replayId");
        UUID viewerId = Objects.requireNonNull(player.getUniqueId(), "player.uniqueId");
        ViewerSession candidate = new ViewerSession(player, replayId);
        ViewerSession existing = entries.putIfAbsent(viewerId, candidate);
        if (existing != null) {
            throw new IllegalStateException("Viewer already has an Example session: " + viewerId);
        }
        return candidate;
    }

    Optional<ViewerSession> find(UUID viewerId) {
        return viewerId == null
                ? Optional.empty()
                : Optional.ofNullable(entries.get(viewerId));
    }

    boolean isProtected(UUID viewerId) {
        ViewerSession entry = viewerId == null ? null : entries.get(viewerId);
        if (entry == null) {
            return false;
        }
        return entry.state() != State.PACK_PENDING;
    }

    boolean transition(ViewerSession entry, State expected, State next) {
        requireOwned(entry);
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        return entry.compareAndSet(expected, next);
    }

    void attachState(ViewerSession entry, StoredPlayerState state) {
        requireOwned(entry);
        entry.storedPlayerState(state);
    }

    void attachPlaybackOpen(ViewerSession entry, CompletionStage<PlaybackSession> playbackOpen) {
        requireOwned(entry);
        entry.playbackOpen(playbackOpen);
    }

    void attachPlayback(ViewerSession entry, PlaybackSession playback) {
        requireOwned(entry);
        entry.playback(playback);
    }

    CompletionStage<Void> beginRestore(ViewerSession entry) {
        requireOwned(entry);
        beginRestoreOwner(entry);
        return entry.restoreStage();
    }

    boolean beginRestoreOwner(ViewerSession entry) {
        requireOwned(entry);
        if (!entry.markRestoreStarted()) {
            return false;
        }

        State current = entry.state();
        if (current != State.PACK_PENDING
                && current != State.PREPARING
                && current != State.PLAYBACK_OPENING
                && current != State.ACTIVE
                && current != State.RESTORING) {
            throw new IllegalStateException("Unknown viewer state: " + current);
        }
        if (current != State.RESTORING && !entry.compareAndSet(current, State.RESTORING)) {
            entry.restoreStarted.set(false);
            return beginRestoreOwner(entry);
        }
        return true;
    }

    boolean isRegistered(ViewerSession entry) {
        return entry != null && entries.get(entry.viewerId()) == entry;
    }

    boolean removeIfSame(ViewerSession entry) {
        Objects.requireNonNull(entry, "entry");
        return entries.remove(entry.viewerId(), entry);
    }

    Collection<ViewerSession> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    private void requireOwned(ViewerSession entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.get(entry.viewerId()) != entry) {
            throw new IllegalStateException("Viewer session is no longer registered");
        }
    }
}
