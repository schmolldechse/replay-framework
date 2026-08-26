package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Renders event-driven playback status without owning a playback scheduler. */
public final class PlaybackStatusRenderer implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ExampleViewerEnvironment environment;
    private final PlaybackHotbar hotbar;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReplayEventPublisher.Subscription subscription;

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
                render(player, snapshot, titleFor(snapshot.status()));
            }
        });
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subscription.close();
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
        UUID finalViewerId = viewerId;
        PlaybackSessionId finalSessionId = sessionId;
        PlaybackSnapshot finalSnapshot = snapshot;
        PlaybackStatus finalTitleStatus = titleStatus;
        onMain(() -> {
            if (closed.get()) {
                return;
            }
            Player player = Bukkit.getPlayer(finalViewerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            Optional<dev.voldechse.replayframework.api.playback.PlaybackSession> session =
                    environment.playback(finalViewerId);
            if (session.isEmpty() || !session.get().id().equals(finalSessionId)) {
                return;
            }
            render(player, finalSnapshot, titleFor(finalTitleStatus));
        });
    }

    private void render(Player player, PlaybackSnapshot snapshot, Optional<Title> title) {
        try {
            player.sendActionBar(Component.text(formatActionBar(snapshot)));
            hotbar.refresh(player.getUniqueId(), snapshot);
            title.ifPresent(player::showTitle);
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Playback status render failed: "
                    + failure.getClass().getSimpleName());
            player.sendActionBar(Component.text(formatActionBar(snapshot)));
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

    private static String formatActionBar(PlaybackSnapshot snapshot) {
        return formatDuration(snapshot.position())
                + " / " + formatDuration(snapshot.duration())
                + " · " + formatSpeed(snapshot)
                + " · " + snapshot.status()
                + " · Buffer " + formatDuration(snapshot.bufferedAhead());
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }

    private static String formatSpeed(PlaybackSnapshot snapshot) {
        double multiplier = snapshot.speed().multiplier();
        return multiplier < 1.0
                ? String.format(Locale.ROOT, "%.2fx", multiplier)
                : String.format(Locale.ROOT, "%.0fx", multiplier);
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
}
