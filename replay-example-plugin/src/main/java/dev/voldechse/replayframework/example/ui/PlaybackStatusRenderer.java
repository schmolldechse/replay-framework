package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Renders the fixed ActionBar status and refreshes active viewers on the main thread. */
public final class PlaybackStatusRenderer implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ExampleViewerEnvironment environment;
    private final PlaybackHotbar hotbar;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<UUID, PlaybackSessionId> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlaybackSessionId> readySessions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> refreshingViewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingRender> pendingRenders = new ConcurrentHashMap<>();
    private final Set<UUID> scheduledRenders = ConcurrentHashMap.newKeySet();
    private final ReplayEventPublisher.Subscription subscription;
    private volatile BukkitTask refreshTask;

    public PlaybackStatusRenderer(
            JavaPlugin plugin,
            ReplayEventPublisher events,
            ExampleViewerEnvironment environment,
            PlaybackHotbar hotbar) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
        this.subscription = Objects.requireNonNull(events, "events").subscribe(this::handleEvent);
    }

    /** Renders the first snapshot after a successful viewer open. */
    public void renderInitial(Player player, PlaybackSnapshot snapshot) {
        if (player == null || snapshot == null) {
            return;
        }
        onMain(() -> {
            if (!closed.get() && player.isOnline()) {
                var session = environment.playback(player.getUniqueId()).orElse(null);
                if (session == null) {
                    return;
                }
                pendingRenders.remove(player.getUniqueId());
                activeSessions.put(player.getUniqueId(), session.id());
                updateRefreshState(player.getUniqueId(), snapshot);
                notifyReady(player, session.id(), snapshot);
                render(player, snapshot, titleFor(snapshot.status()));
            }
        });
    }

    /** Clears status for a viewer that left the browser or playback environment. */
    public void clear(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        onMain(() -> {
            pendingRenders.remove(viewerId);
            scheduledRenders.remove(viewerId);
            activeSessions.remove(viewerId);
            readySessions.remove(viewerId);
            refreshingViewers.remove(viewerId);
            cancelRefreshIfIdle();
            Player player = Bukkit.getPlayer(viewerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.empty());
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subscription.close();
            BukkitTask task = refreshTask;
            if (task != null) {
                task.cancel();
                refreshTask = null;
            }
            for (UUID viewerId : activeSessions.keySet()) {
                Player player = Bukkit.getPlayer(viewerId);
                if (player != null && player.isOnline()) {
                    player.sendActionBar(Component.empty());
                }
            }
            activeSessions.clear();
            readySessions.clear();
            refreshingViewers.clear();
            pendingRenders.clear();
            scheduledRenders.clear();
        }
    }

    private void handleEvent(ReplayEventPublisher.ReplayEvent event) {
        if (closed.get()) {
            return;
        }
        UUID viewerId = null;
        PlaybackSessionId sessionId = null;
        PlaybackSnapshot snapshot = null;
        PlaybackStatus titleStatus = null;
        if (event instanceof ReplayEventPublisher.PlaybackStatusChanged changed) {
            viewerId = changed.viewerId();
            sessionId = changed.sessionId();
            snapshot = changed.snapshot();
            titleStatus = changed.current();
        } else if (event instanceof ReplayEventPublisher.PlaybackSpeedChanged changed) {
            viewerId = changed.viewerId();
            sessionId = changed.sessionId();
            snapshot = changed.snapshot();
        } else if (event instanceof ReplayEventPublisher.PlaybackSeeked changed) {
            viewerId = changed.viewerId();
            sessionId = changed.sessionId();
            snapshot = changed.snapshot();
        } else if (event instanceof ReplayEventPublisher.PlaybackBufferChanged changed) {
            viewerId = changed.viewerId();
            sessionId = changed.sessionId();
            snapshot = changed.snapshot();
        } else if (event instanceof ReplayEventPublisher.PlaybackCompleted completed) {
            viewerId = completed.viewerId();
            sessionId = completed.sessionId();
            snapshot = completed.snapshot();
            titleStatus = completed.finalStatus();
        }
        if (viewerId == null || sessionId == null || snapshot == null) {
            return;
        }
        queueRender(new PendingRender(viewerId, sessionId, snapshot, titleStatus));
    }

    private void queueRender(PendingRender pending) {
        pendingRenders.put(pending.viewerId(), pending);
        if (!scheduledRenders.add(pending.viewerId())) {
            return;
        }
        onMain(() -> drainRender(pending.viewerId()));
    }

    private void drainRender(UUID viewerId) {
        try {
            PendingRender pending = pendingRenders.remove(viewerId);
            if (pending != null) {
                renderEvent(pending);
            }
        } finally {
            scheduledRenders.remove(viewerId);
            if (pendingRenders.containsKey(viewerId) && scheduledRenders.add(viewerId)) {
                onMain(() -> drainRender(viewerId));
            }
        }
    }

    private void renderEvent(PendingRender pending) {
        if (closed.get()) {
            return;
        }
        Player player = Bukkit.getPlayer(pending.viewerId());
        if (player == null || !player.isOnline()) {
            return;
        }
        Optional<dev.voldechse.replayframework.api.playback.PlaybackSession> session =
                environment.playback(pending.viewerId());
        if (session.isEmpty() || !session.get().id().equals(pending.sessionId())) {
            return;
        }
        if (pending.snapshot().status() == PlaybackStatus.FAILED
                || pending.snapshot().status() == PlaybackStatus.CLOSED) {
            activeSessions.remove(pending.viewerId(), pending.sessionId());
            refreshingViewers.remove(pending.viewerId());
            cancelRefreshIfIdle();
            hotbar.clear(pending.viewerId());
            player.sendActionBar(Component.empty());
            environment.leave(pending.viewerId());
            return;
        }
        activeSessions.put(pending.viewerId(), pending.sessionId());
        updateRefreshState(pending.viewerId(), pending.snapshot());
        notifyReady(player, pending.sessionId(), pending.snapshot());
        render(player, pending.snapshot(), titleFor(pending.titleStatus()));
    }

    private void refreshActive() {
        if (closed.get()) {
            return;
        }
        for (UUID viewerId : refreshingViewers) {
            PlaybackSessionId sessionId = activeSessions.get(viewerId);
            if (sessionId == null) {
                refreshingViewers.remove(viewerId);
                continue;
            }
            var session = environment.playback(viewerId).orElse(null);
            Player player = Bukkit.getPlayer(viewerId);
            if (session == null || player == null || !player.isOnline()
                    || !session.id().equals(sessionId)) {
                activeSessions.remove(viewerId, sessionId);
                refreshingViewers.remove(viewerId);
                if (player != null && player.isOnline()) {
                    player.sendActionBar(Component.empty());
                }
                continue;
            }
            PlaybackSnapshot snapshot = session.snapshot();
            render(player, snapshot, Optional.empty());
            if (isTerminal(snapshot.status())) {
                refreshingViewers.remove(viewerId);
            }
        }
        cancelRefreshIfIdle();
    }

    private void updateRefreshState(UUID viewerId, PlaybackSnapshot snapshot) {
        if (!isTerminal(snapshot.status())) {
            refreshingViewers.add(viewerId);
            ensureRefreshTask();
        } else {
            refreshingViewers.remove(viewerId);
            cancelRefreshIfIdle();
        }
        if (isTerminal(snapshot.status())) {
            activeSessions.remove(viewerId);
        }
    }

    private static boolean isTerminal(PlaybackStatus status) {
        return status == PlaybackStatus.FAILED || status == PlaybackStatus.CLOSED;
    }

    private void ensureRefreshTask() {
        if (refreshTask == null && !closed.get()) {
            refreshTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin, this::refreshActive, 1L, 2L);
        }
    }

    private void cancelRefreshIfIdle() {
        if (!refreshingViewers.isEmpty()) {
            return;
        }
        BukkitTask task = refreshTask;
        if (task != null) {
            task.cancel();
            refreshTask = null;
        }
    }

    private void render(Player player, PlaybackSnapshot snapshot, Optional<Title> title) {
        try {
            player.sendActionBar(PlaybackStatusLayout.component(snapshot));
            hotbar.refresh(player.getUniqueId(), snapshot);
            title.ifPresent(player::showTitle);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Playback status render failed: "
                    + failure.getClass().getSimpleName());
            player.sendActionBar(PlaybackStatusLayout.component(snapshot));
        }
    }

    private static Optional<Title> titleFor(PlaybackStatus status) {
        if (status == null) {
            return Optional.empty();
        }
        return switch (status) {
            case PREPARING -> Optional.of(Title.title(
                    Component.text("Preparing replay"), Component.text("Please wait")));
            case BUFFERING -> Optional.of(Title.title(
                    Component.text("Buffering replay"), Component.text("Loading timeline data")));
            case ENDED -> Optional.of(Title.title(
                    Component.text("Replay ended"), Component.text("Use restart to play again")));
            case FAILED -> Optional.of(Title.title(
                    Component.text("Replay failed"), Component.text("The viewer was stopped")));
            case PLAYING, PAUSED, CLOSED -> Optional.empty();
        };
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (!closed.get() && plugin.isEnabled()) {
            try {
                plugin.getServer().getScheduler().runTask(plugin, action);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.FINE, "Playback status task rejected during shutdown");
            }
        }
    }

    private void notifyReady(
            Player player,
            PlaybackSessionId sessionId,
            PlaybackSnapshot snapshot) {
        if (snapshot.status() != PlaybackStatus.PLAYING) {
            return;
        }
        PlaybackSessionId previous = readySessions.put(player.getUniqueId(), sessionId);
        if (!sessionId.equals(previous)) {
            player.sendMessage(Component.text(
                    "Replay loaded and ready. The hotbar controls are now active."));
        }
    }

    private record PendingRender(
            UUID viewerId,
            PlaybackSessionId sessionId,
            PlaybackSnapshot snapshot,
            PlaybackStatus titleStatus) {
    }
}
