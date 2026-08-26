package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.id.PlaybackSessionId;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackSnapshot;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.playback.PlaybackStatus;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Installs and controls the Example playback hotbar for active viewers. */
public final class PlaybackHotbar implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ExampleViewerEnvironment environment;
    private final ExampleItemModels models;
    private final Map<UUID, PlaybackSessionId> activeSessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PlaybackHotbar(
            JavaPlugin plugin,
            ExampleViewerEnvironment environment,
            ExampleItemModels models) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.models = Objects.requireNonNull(models, "models");
    }

    /** Installs the six configured controls for one newly opened playback session. */
    public void install(Player player, PlaybackSession session) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(session, "session");
        onMain(() -> {
            if (closed.get() || !player.isOnline() || !environment.isViewer(player.getUniqueId())) {
                return;
            }
            activeSessions.put(player.getUniqueId(), session.id());
            render(player, session.snapshot());
        });
    }

    /** Refreshes controls only when the snapshot belongs to the active session. */
    public void refresh(UUID viewerId, PlaybackSnapshot snapshot) {
        if (viewerId == null || snapshot == null) {
            return;
        }
        onMain(() -> {
            if (closed.get()) {
                return;
            }
            Player player = Bukkit.getPlayer(viewerId);
            PlaybackSession session = environment.playback(viewerId).orElse(null);
            if (player == null || !player.isOnline() || session == null
                    || !session.id().equals(activeSessions.get(viewerId))) {
                return;
            }
            render(player, snapshot);
        });
    }

    /** Handles one already validated Example control item. */
    void handle(Player player, ExampleItemModels.HotbarAction action) {
        if (player == null || action == null) {
            return;
        }
        onMain(() -> {
            if (closed.get() || !player.isOnline() || !environment.isViewer(player.getUniqueId())) {
                return;
            }
            UUID viewerId = player.getUniqueId();
            PlaybackSession session = environment.playback(viewerId).orElse(null);
            if (session == null || !session.id().equals(activeSessions.get(viewerId))) {
                return;
            }
            PlaybackSnapshot snapshot = session.snapshot();
            if (!allowed(snapshot.status(), action)) {
                return;
            }
            try {
                switch (action) {
                    case TOGGLE_PLAY -> {
                        if (snapshot.status() == PlaybackStatus.PLAYING) {
                            session.pause();
                        } else if (snapshot.status() == PlaybackStatus.PAUSED) {
                            session.play();
                        }
                        render(player, session.snapshot());
                    }
                    case REWIND -> seek(player, session, Duration.ofSeconds(-models.hotbar().rewindSeconds()));
                    case FORWARD -> seek(player, session, Duration.ofSeconds(models.hotbar().forwardSeconds()));
                    case RESTART -> complete(player, session, session.restart());
                    case SPEED_CYCLE -> session.speed(nextSpeed(snapshot.speed()));
                    case LEAVE -> environment.leave(viewerId).whenComplete((ignored, failure) -> onMain(() -> {
                        if (failure != null) {
                            player.sendMessage(Component.text(
                                    "Replay viewer could not be closed.", NamedTextColor.RED));
                        }
                        clear(viewerId);
                    }));
                }
                if (action == ExampleItemModels.HotbarAction.SPEED_CYCLE) {
                    render(player, session.snapshot());
                }
            } catch (Throwable failure) {
                reportFailure(player, failure);
            }
        });
    }

    /** Clears only recognized temporary Example controls for one viewer. */
    public void clear(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        onMain(() -> {
            activeSessions.remove(viewerId);
            Player player = Bukkit.getPlayer(viewerId);
            if (player == null) {
                return;
            }
            for (ExampleItemModels.ItemDefinition definition : models.hotbar().items()) {
                ItemStack item = player.getInventory().getItem(definition.slot());
                if (models.controlAction(item).filter(definition.action()::equals).isPresent()) {
                    player.getInventory().setItem(definition.slot(), null);
                }
            }
        });
    }

    /** Returns whether a player currently owns a session bound to a hotbar. */
    boolean owns(UUID viewerId) {
        return viewerId != null && activeSessions.containsKey(viewerId);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (UUID viewerId : List.copyOf(activeSessions.keySet())) {
            activeSessions.remove(viewerId);
        }
    }

    private void seek(Player player, PlaybackSession session, Duration delta) {
        try {
            complete(player, session, session.seekBy(delta));
        } catch (Throwable failure) {
            reportFailure(player, failure);
        }
    }

    private void complete(Player player, PlaybackSession session, CompletionStage<PlaybackSnapshot> stage) {
        if (stage == null) {
            reportFailure(player, new NullPointerException("playback operation returned no stage"));
            return;
        }
        stage.whenComplete((snapshot, failure) -> onMain(() -> {
            if (failure != null) {
                reportFailure(player, failure);
            } else if (snapshot != null) {
                refresh(player.getUniqueId(), snapshot);
            }
        }));
    }

    private static boolean allowed(PlaybackStatus status, ExampleItemModels.HotbarAction action) {
        if (status == PlaybackStatus.PREPARING
                || status == PlaybackStatus.BUFFERING
                || status == PlaybackStatus.FAILED
                || status == PlaybackStatus.CLOSED) {
            return false;
        }
        return status != PlaybackStatus.ENDED
                || action == ExampleItemModels.HotbarAction.RESTART
                || action == ExampleItemModels.HotbarAction.LEAVE;
    }

    private static PlaybackSpeed nextSpeed(PlaybackSpeed current) {
        PlaybackSpeed[] speeds = PlaybackSpeed.values();
        return speeds[(current.ordinal() + 1) % speeds.length];
    }

    private void render(Player player, PlaybackSnapshot snapshot) {
        boolean playing = snapshot.status() == PlaybackStatus.PLAYING;
        for (ExampleItemModels.ItemDefinition definition : models.hotbar().items()) {
            Component name = Component.text(displayName(definition.action(), snapshot));
            ItemStack item = models.controlItem(
                    definition.action(), playing, snapshot.speed(), name, List.of());
            player.getInventory().setItem(definition.slot(), item);
        }
    }

    private String displayName(
            ExampleItemModels.HotbarAction action, PlaybackSnapshot snapshot) {
        return switch (action) {
            case TOGGLE_PLAY -> snapshot.status() == PlaybackStatus.PLAYING ? "Pause" : "Play";
            case REWIND -> "Rewind " + models.hotbar().rewindSeconds() + "s";
            case FORWARD -> "Forward " + models.hotbar().forwardSeconds() + "s";
            case RESTART -> "Restart";
            case SPEED_CYCLE -> "Speed " + snapshot.speed().multiplier() + "x";
            case LEAVE -> "Leave replay";
        };
    }

    private void reportFailure(Player player, Throwable failure) {
        plugin.getLogger().log(Level.FINE, "Playback hotbar operation failed: "
                + failure.getClass().getSimpleName());
        if (player.isOnline()) {
            player.sendMessage(Component.text(
                    "Playback control failed. Please try again.", NamedTextColor.RED));
        }
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (!closed.get() && plugin.isEnabled()) {
            try {
                plugin.getServer().getScheduler().runTask(plugin, action);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.FINE, "Playback hotbar task rejected during shutdown");
            }
        }
    }
}
