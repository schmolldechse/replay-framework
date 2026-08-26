package dev.voldechse.replayframework.example.viewer;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.api.playback.PlaybackRequest;
import dev.voldechse.replayframework.api.playback.PlaybackService;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.example.resourcepack.ExampleResourcePackService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.logging.Level;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

/**
 * Owns the Example-side environment around one independent replay viewer.
 *
 * <p>The environment is deliberately separate from the framework playback
 * service. It prepares and restores the Paper player, while the framework
 * owns the playback session and its resources.</p>
 */
public final class ExampleViewerEnvironment {

    /**
     * Immutable configuration for the Example viewer environment.
     *
     * @param replayLocation target location used while viewing
     * @param playbackGameMode game mode used while viewing
     * @param bufferOptions session-local playback buffer options
     * @param allowedCommandRoots command roots that remain available
     * @param controlItemMatcher server-side control-item predicate
     */
    public record Configuration(
            Location replayLocation,
            GameMode playbackGameMode,
            PlaybackBufferOptions bufferOptions,
            Set<String> allowedCommandRoots,
            ViewerProtectionListener.ControlItemMatcher controlItemMatcher) {

        /** Validates and defensively copies the mutable configuration values. */
        public Configuration {
            Objects.requireNonNull(replayLocation, "replayLocation");
            Objects.requireNonNull(playbackGameMode, "playbackGameMode");
            Objects.requireNonNull(bufferOptions, "bufferOptions");
            Objects.requireNonNull(allowedCommandRoots, "allowedCommandRoots");
            if (replayLocation.getWorld() == null
                    || !Double.isFinite(replayLocation.getX())
                    || !Double.isFinite(replayLocation.getY())
                    || !Double.isFinite(replayLocation.getZ())
                    || !Float.isFinite(replayLocation.getYaw())
                    || !Float.isFinite(replayLocation.getPitch())) {
                throw new IllegalArgumentException("replayLocation must have a world and finite coordinates");
            }

            Set<String> normalizedRoots = new HashSet<>();
            for (String root : allowedCommandRoots) {
                Objects.requireNonNull(root, "allowedCommandRoots entry");
                if (root.isBlank()
                        || !root.equals(root.strip())
                        || root.indexOf('/') >= 0
                        || root.chars().anyMatch(Character::isWhitespace)) {
                    throw new IllegalArgumentException("Command roots must be non-empty names without whitespace or '/'");
                }
                normalizedRoots.add(root.toLowerCase(Locale.ROOT));
            }

            replayLocation = replayLocation.clone();
            allowedCommandRoots = Set.copyOf(normalizedRoots);
            controlItemMatcher = controlItemMatcher == null ? item -> false : controlItemMatcher;
        }

        /**
         * Returns a defensive copy so callers cannot mutate the target used
         * by a later viewer.
         *
         * @return copied replay location
         */
        @Override
        public Location replayLocation() {
            return replayLocation.clone();
        }

        /**
         * Creates the conservative default Example configuration.
         *
         * @param replayLocation target location for viewers
         * @return default configuration
         */
        public static Configuration defaults(Location replayLocation) {
            return new Configuration(
                    replayLocation,
                    GameMode.ADVENTURE,
                    PlaybackBufferOptions.builder().build(),
                    Set.of("replay"),
                    item -> false);
        }
    }

    private final JavaPlugin plugin;
    private final PlaybackService playbackService;
    private final ExampleResourcePackService resourcePackService;
    private final Configuration configuration;
    private final ViewerSessionRegistry registry = new ViewerSessionRegistry();
    private final ViewerProtectionListener protectionListener;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> closeStage = new CompletableFuture<>();

    /**
     * Creates and registers the Example viewer environment.
     *
     * @param plugin owning Example plugin
     * @param playbackService public framework playback service
     * @param resourcePackService Example resource-pack handshake service
     * @param configuration viewer preparation configuration
     */
    public ExampleViewerEnvironment(
            JavaPlugin plugin,
            PlaybackService playbackService,
            ExampleResourcePackService resourcePackService,
            Configuration configuration) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playbackService = Objects.requireNonNull(playbackService, "playbackService");
        this.resourcePackService = Objects.requireNonNull(resourcePackService, "resourcePackService");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.protectionListener = new ViewerProtectionListener(
                registry,
                configuration.allowedCommandRoots(),
                configuration.controlItemMatcher(),
                this::handleDisconnect);
        protectionListener.register(plugin.getServer().getPluginManager(), plugin);
    }

    /**
     * Opens one viewer after the configured resource-pack handshake succeeds.
     *
     * @param player Paper viewer
     * @param replayId replay to open
     * @return stage completed with the active playback session
     */
    public CompletionStage<PlaybackSession> open(Player player, ReplayId replayId) {
        if (player == null) {
            return failed(new NullPointerException("player"));
        }
        if (replayId == null) {
            return failed(new NullPointerException("replayId"));
        }

        return onMain(() -> {
            if (closing.get()) {
                throw new IllegalStateException("Viewer environment is closing");
            }
            if (!player.isOnline()) {
                throw new IllegalStateException("Viewer is offline");
            }
            return registry.reserve(player, replayId);
        }).thenCompose(entry -> {
            CompletionStage<ExampleResourcePackService.HandshakeResult> packStage;
            try {
                packStage = resourcePackService.request(entry.player());
            } catch (Throwable failure) {
                return failOpen(entry, failure);
            }
            if (packStage == null) {
                return failOpen(entry, new NullPointerException("resourcePackService.request returned null"));
            }
            return packStage
                    .thenCompose(ignored -> onMain(() -> prepareAndStart(entry)))
                    .thenCompose(Function.identity())
                    .handle((session, failure) -> failure == null
                            ? CompletableFuture.completedFuture(session)
                            : failOpen(entry, unwrap(failure)))
                    .thenCompose(Function.identity());
        });
    }

    /**
     * Leaves the viewer represented by the supplied player.
     *
     * @param player viewer to leave
     * @return stage completed after close and restore
     */
    public CompletionStage<Void> leave(Player player) {
        if (player == null) {
            return failed(new NullPointerException("player"));
        }
        return onMain(player::getUniqueId).thenCompose(this::leave);
    }

    /**
     * Leaves a viewer by UUID. The operation is idempotent.
     *
     * @param viewerId viewer UUID
     * @return stage completed after close and restore
     */
    public CompletionStage<Void> leave(UUID viewerId) {
        if (viewerId == null) {
            return failed(new NullPointerException("viewerId"));
        }
        return onMain(() -> registry.find(viewerId)
                        .map(entry -> restoreEntry(entry,
                                new CancellationException("Viewer left the Example environment")))
                        .orElseGet(() -> CompletableFuture.completedFuture(null)))
                .thenCompose(Function.identity());
    }

    /**
     * Returns the active playback session of a viewer, if one exists.
     *
     * @param viewerId viewer UUID
     * @return active playback session
     */
    public Optional<PlaybackSession> playback(UUID viewerId) {
        return registry.find(viewerId).map(ViewerSessionRegistry.ViewerSession::playback);
    }

    /**
     * Returns whether the UUID is reserved by this Example environment.
     *
     * @param viewerId viewer UUID
     * @return true while the lifecycle entry exists
     */
    public boolean isViewer(UUID viewerId) {
        return registry.find(viewerId).isPresent();
    }

    /**
     * Stops new opens and restores all currently owned viewers.
     *
     * @return reusable stage completed after listener teardown and all restores
     */
    public CompletionStage<Void> close() {
        if (closing.compareAndSet(false, true)) {
            onMain(this::closeEntriesOnMain)
                    .thenCompose(Function.identity())
                    .whenComplete(this::finishClose);
        }
        return closeStage;
    }

    private CompletionStage<PlaybackSession> prepareAndStart(ViewerSessionRegistry.ViewerSession entry) {
        if (closing.get()) {
            throw new CancellationException("Viewer environment is closing");
        }
        Player player = entry.player();
        if (!player.isOnline()) {
            throw new IllegalStateException("Viewer went offline before preparation");
        }
        StoredPlayerState state = StoredPlayerState.capture(player);
        registry.attachState(entry, state);
        World viewerWorld = createViewerWorld(entry);
        registry.attachViewerWorld(entry, viewerWorld);
        if (!registry.transition(
                entry,
                ViewerSessionRegistry.State.PACK_PENDING,
                ViewerSessionRegistry.State.PREPARING)) {
            throw new IllegalStateException("Viewer was no longer pending preparation");
        }

        preparePlayer(player, viewerWorld);

        if (!registry.transition(
                entry,
                ViewerSessionRegistry.State.PREPARING,
                ViewerSessionRegistry.State.PLAYBACK_OPENING)) {
            throw new IllegalStateException("Viewer preparation state changed unexpectedly");
        }

        PlaybackRequest request = PlaybackRequest.builder()
                .replay(entry.replayId())
                .viewer(player)
                .buffer(configuration.bufferOptions())
                .build();
        CompletionStage<PlaybackSession> openStage = playbackService.open(request);
        if (openStage == null) {
            throw new NullPointerException("playbackService.open returned null");
        }
        registry.attachPlaybackOpen(entry, openStage);
        openStage.whenComplete((session, failure) -> {
            onMain(() -> {
                handlePlaybackCompletion(entry, session, failure);
                return null;
            }).whenComplete((ignored, callbackFailure) -> {
                if (callbackFailure != null && registry.isRegistered(entry)) {
                    failOpen(entry, unwrap(callbackFailure));
                }
            });
        });
        return entry.openResult();
    }

    private void preparePlayer(Player player, World viewerWorld) {
        Location target = configuration.replayLocation();
        target.setWorld(viewerWorld);
        if (!player.teleport(target)) {
            throw new IllegalStateException("Viewer teleportation was rejected");
        }
        player.setGameMode(configuration.playbackGameMode());
        PlayerInventory inventory = Objects.requireNonNull(player.getInventory(), "player.inventory");
        inventory.clear();
        inventory.setArmorContents(new ItemStack[4]);
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
        player.setItemOnCursor(new ItemStack(Material.AIR));
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        player.setInvisible(false);
        player.setGlowing(false);
        player.setCollidable(false);
        player.setGravity(false);
        player.setFallDistance(0.0F);
    }

    private World createViewerWorld(ViewerSessionRegistry.ViewerSession entry) {
        String worldName = "replay-viewer-" + entry.viewerId().toString().replace("-", "")
                + "-" + entry.replayId().value().toString().replace("-", "");
        if (plugin.getServer().getWorld(worldName) != null) {
            throw new IllegalStateException("Viewer world name is already in use: " + worldName);
        }
        World template = Objects.requireNonNull(
                configuration.replayLocation().getWorld(),
                "replayLocation.world");
        World world = new WorldCreator(worldName)
                .environment(template.getEnvironment())
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();
        if (world == null) {
            throw new IllegalStateException("Paper did not create the viewer world");
        }
        world.setAutoSave(false);
        return world;
    }

    private void handlePlaybackCompletion(
            ViewerSessionRegistry.ViewerSession entry,
            PlaybackSession session,
            Throwable failure) {
        if (!registry.isRegistered(entry)) {
            if (failure == null && session != null) {
                closeUnattachedSession(entry, session);
            }
            return;
        }
        if (failure != null) {
            failOpen(entry, unwrap(failure));
            return;
        }
        if (session == null) {
            failOpen(entry, new NullPointerException("playbackService.open completed with null"));
            return;
        }

        if (entry.state() == ViewerSessionRegistry.State.PLAYBACK_OPENING
                && registry.transition(
                        entry,
                        ViewerSessionRegistry.State.PLAYBACK_OPENING,
                        ViewerSessionRegistry.State.ACTIVE)) {
            try {
                registry.attachPlayback(entry, session);
                session.play();
                entry.openResult().complete(session);
            } catch (Throwable attachFailure) {
                failOpen(entry, attachFailure);
            }
            return;
        }
        if (entry.state() == ViewerSessionRegistry.State.RESTORING) {
            entry.openResult().completeExceptionally(
                    new CancellationException("Viewer restore started before playback opened"));
            return;
        }
        failOpen(entry, new IllegalStateException("Playback completed outside its opening state"));
    }

    private CompletionStage<PlaybackSession> failOpen(
            ViewerSessionRegistry.ViewerSession entry,
            Throwable failure) {
        Throwable cause = unwrap(failure);
        return restoreEntry(entry, cause)
                .thenCompose(ignored -> failed(cause));
    }

    private CompletionStage<Void> restoreEntry(
            ViewerSessionRegistry.ViewerSession entry,
            Throwable terminalFailure) {
        if (!registry.isRegistered(entry)) {
            return CompletableFuture.completedFuture(null);
        }
        boolean owner = registry.beginRestoreOwner(entry);
        if (!owner) {
            return entry.restoreStage();
        }

        try {
            resourcePackService.invalidate(entry.player());
        } catch (Throwable failure) {
            logFailure("resource-pack invalidation", entry, failure);
        }

        if (entry.state() == ViewerSessionRegistry.State.RESTORING
                && entry.storedPlayerState() == null
                && entry.playbackOpen() == null
                && entry.playback() == null) {
            return finishRestoreOnMain(entry, terminalFailure);
        }

        return closePlaybackBeforeRestore(entry)
                .thenCompose(ignored -> onMain(() -> restorePlayerAndRelease(entry, terminalFailure)));
    }

    private CompletionStage<Void> closePlaybackBeforeRestore(
            ViewerSessionRegistry.ViewerSession entry) {
        PlaybackSession playback = entry.playback();
        if (playback != null) {
            return closeSession(entry, playback);
        }
        CompletionStage<PlaybackSession> pendingOpen = entry.playbackOpen();
        if (pendingOpen == null) {
            return CompletableFuture.completedFuture(null);
        }
        return pendingOpen
                .handle((session, failure) -> {
                    if (failure != null || session == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return closeSession(entry, session);
                })
                .thenCompose(Function.identity());
    }

    private CompletionStage<Void> closeSession(
            ViewerSessionRegistry.ViewerSession entry,
            PlaybackSession playback) {
        CompletionStage<?> closeStage;
        try {
            closeStage = playback.close();
            if (closeStage == null) {
                closeStage = failed(new NullPointerException("PlaybackSession.close returned null"));
            }
        } catch (Throwable failure) {
            closeStage = failed(failure);
        }
        return closeStage.handle((ignored, failure) -> {
            if (failure != null) {
                logFailure("playback close", entry, unwrap(failure));
            }
            return null;
        });
    }

    private void closeUnattachedSession(
            ViewerSessionRegistry.ViewerSession entry,
            PlaybackSession session) {
        closeSession(entry, session).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logFailure("unattached playback close", entry, unwrap(failure));
            }
        });
    }

    private Void restorePlayerAndRelease(
            ViewerSessionRegistry.ViewerSession entry,
            Throwable terminalFailure) {
        StoredPlayerState state = entry.storedPlayerState();
        if (state != null) {
            try {
                state.restore(entry.player());
            } catch (Throwable restoreFailure) {
                logFailure("player restore", entry, restoreFailure);
            }
        }
        deleteViewerWorld(entry);
        finishRestoreOnMain(entry, terminalFailure);
        return null;
    }

    private void deleteViewerWorld(ViewerSessionRegistry.ViewerSession entry) {
        World world = entry.viewerWorld();
        if (world == null) {
            return;
        }

        try {
            if (plugin.getServer().getWorld(world.getName()) != null
                    && !plugin.getServer().unloadWorld(world, false)) {
                throw new IllegalStateException("Viewer world could not be unloaded");
            }
        } catch (Throwable failure) {
            logFailure("viewer world unload", entry, failure);
            return;
        }

        Path container = plugin.getServer().getWorldContainer().toPath()
                .toAbsolutePath().normalize();
        Path folder = world.getWorldFolder().toPath()
                .toAbsolutePath().normalize();
        String expectedPrefix = "replay-viewer-";
        if (!folder.startsWith(container)
                || folder.getFileName() == null
                || !folder.getFileName().toString().startsWith(expectedPrefix)) {
            logFailure("viewer world delete", entry,
                    new IllegalStateException("viewer world path is outside the owned world container"));
            return;
        }

        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException failure) {
            logFailure("viewer world delete", entry, failure);
        }
    }

    private CompletionStage<Void> finishRestoreOnMain(
            ViewerSessionRegistry.ViewerSession entry,
            Throwable terminalFailure) {
        logTerminalFailureIfNeeded(entry, terminalFailure);
        if (!entry.openResult().isDone()) {
            entry.openResult().completeExceptionally(unwrap(terminalFailure));
        }
        registry.removeIfSame(entry);
        entry.completeRestore();
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> closeEntriesOnMain() {
        List<CompletionStage<Void>> restores = new ArrayList<>();
        for (ViewerSessionRegistry.ViewerSession entry : registry.snapshot()) {
            restores.add(restoreEntry(
                    entry,
                    new CancellationException("Viewer environment closed")));
        }
        if (restores.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] stages = restores.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(stages);
    }

    private void handleDisconnect(Player player) {
        leave(player.getUniqueId());
    }

    private void finishClose(Void ignored, Throwable failure) {
        finishClose(failure);
    }

    private void finishClose(Throwable failure) {
        Runnable finish = () -> {
            protectionListener.unregister();
            if (failure == null) {
                closeStage.complete(null);
            } else {
                closeStage.completeExceptionally(unwrap(failure));
            }
        };
        if (plugin.getServer().isPrimaryThread()) {
            finish.run();
        } else {
            try {
                plugin.getServer().getScheduler().runTask(plugin, finish);
            } catch (Throwable schedulingFailure) {
                protectionListener.unregister();
                closeStage.completeExceptionally(unwrap(schedulingFailure));
            }
        }
    }

    private void logTerminalFailureIfNeeded(
            ViewerSessionRegistry.ViewerSession entry,
            Throwable failure) {
        if (failure != null && !(unwrap(failure) instanceof CancellationException)) {
            logFailure("viewer lifecycle", entry, unwrap(failure));
        }
    }

    private void logFailure(
            String operation,
            ViewerSessionRegistry.ViewerSession entry,
            Throwable failure) {
        Throwable cause = unwrap(failure);
        plugin.getLogger().log(
                Level.WARNING,
                "Example viewer operation '" + operation
                        + "' failed for viewer " + entry.viewerId()
                        + " and replay " + entry.replayId()
                        + " (" + cause.getClass().getSimpleName() + ")");
    }

    private <T> CompletionStage<T> onMain(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable invoke = () -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        try {
            if (plugin.getServer().isPrimaryThread()) {
                invoke.run();
            } else {
                plugin.getServer().getScheduler().runTask(plugin, invoke);
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }
}
