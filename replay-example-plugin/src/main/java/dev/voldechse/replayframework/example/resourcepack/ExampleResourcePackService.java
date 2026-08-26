package dev.voldechse.replayframework.example.resourcepack;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Coordinates the Example resource-pack request for individual viewers.
 *
 * <p>The service intentionally stops at the client confirmation boundary. It
 * does not prepare a player or open a playback session.</p>
 */
public final class ExampleResourcePackService implements AutoCloseable {

    @FunctionalInterface
    interface PackRequestSender {
        void send(Player player, ResourcePackRequest request);
    }

    /**
     * Immutable configuration for one Example resource-pack artifact.
     *
     * @param uri externally hosted HTTPS resource-pack URI
     * @param sha1 lowercase SHA-1 digest of the complete ZIP artifact
     * @param handshakeTimeout maximum time allowed for the client handshake
     * @param prompt optional client prompt shown with the required request
     */
    public record Configuration(
            URI uri,
            String sha1,
            Duration handshakeTimeout,
            Component prompt) {

        /** Validates the immutable request configuration at construction time. */
        public Configuration {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(sha1, "sha1");
            Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
            Objects.requireNonNull(prompt, "prompt");
            if (!uri.isAbsolute()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("Resource-pack URI must be an absolute HTTPS URI without credentials or fragments");
            }
            if (!sha1.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("Resource-pack SHA-1 must be lowercase hexadecimal with 40 characters");
            }
            if (handshakeTimeout.isZero() || handshakeTimeout.isNegative()) {
                throw new IllegalArgumentException("handshakeTimeout must be positive");
            }
        }

        /**
         * Returns the stable identity used by Minecraft to correlate this
         * pack's callback events.
         *
         * @return deterministic UUID derived from the configured SHA-1
         */
        public UUID packId() {
            String normalizedHash = sha1.toLowerCase(Locale.ROOT);
            return UUID.nameUUIDFromBytes(("replay-example-resource-pack:" + normalizedHash)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Immutable successful result returned to the next Example lifecycle stage.
     *
     * @param viewerId viewer that completed the handshake
     * @param packId deterministic identity of the applied pack
     * @param uri URI used for the request
     * @param status successful terminal status
     */
    public record HandshakeResult(
            UUID viewerId,
            UUID packId,
            URI uri,
            ResourcePackStatus status) {

        /** Validates that a result can represent only a successful handshake. */
        public HandshakeResult {
            Objects.requireNonNull(viewerId, "viewerId");
            Objects.requireNonNull(packId, "packId");
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(status, "status");
            if (status != ResourcePackStatus.SUCCESSFULLY_LOADED) {
                throw new IllegalArgumentException("HandshakeResult requires SUCCESSFULLY_LOADED");
            }
        }
    }

    /** Exception used for a terminal client-reported pack failure. */
    public static final class HandshakeException extends RuntimeException {
        /** Viewer whose request reached a terminal failure. */
        private final UUID viewerId;
        /** Client status that caused the terminal failure. */
        private final ResourcePackStatus status;

        HandshakeException(UUID viewerId, ResourcePackStatus status) {
            super("Resource-pack handshake failed for viewer " + viewerId + " with status " + status);
            this.viewerId = viewerId;
            this.status = status;
        }

        /**
         * Returns the viewer associated with this failure.
         *
         * @return viewer UUID
         */
        public UUID viewerId() {
            return viewerId;
        }

        /**
         * Returns the terminal client status that caused this failure.
         *
         * @return terminal resource-pack status
         */
        public ResourcePackStatus status() {
            return status;
        }
    }

    private enum State {
        // Intermediate states remain retryable; READY, FAILED and CLOSED are terminal.
        REQUESTED,
        ACCEPTED,
        DOWNLOADED,
        READY,
        FAILED,
        CLOSED
    }

    private static final class PendingHandshake {
        private final Player player;
        private final UUID viewerId;
        private final CompletableFuture<HandshakeResult> future = new CompletableFuture<>();
        private State state = State.REQUESTED;
        private boolean terminal;
        private ScheduledFuture<?> timeout;

        private PendingHandshake(Player player) {
            this.player = player;
            this.viewerId = player.getUniqueId();
        }
    }

    private final Configuration configuration;
    private final PackRequestSender requestSender;
    private final Executor completionExecutor;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final ConcurrentMap<UUID, PendingHandshake> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Player> loadedConnections = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    /**
     * Creates the runtime service using Paper's scheduler and a private
     * daemon scheduler for handshake timeouts.
     *
     * @param plugin owning Example plugin
     * @param configuration immutable pack configuration
     */
    public ExampleResourcePackService(JavaPlugin plugin, Configuration configuration) {
        this(
                configuration,
                Player::sendResourcePacks,
                paperCompletionExecutor(plugin),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "replay-example-resource-pack-timeouts");
                    thread.setDaemon(true);
                    return thread;
                }),
                true);
    }

    ExampleResourcePackService(
            Configuration configuration,
            PackRequestSender requestSender,
            Executor completionExecutor,
            ScheduledExecutorService scheduler) {
        this(configuration, requestSender, completionExecutor, scheduler, false);
    }

    private ExampleResourcePackService(
            Configuration configuration,
            PackRequestSender requestSender,
            Executor completionExecutor,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.requestSender = Objects.requireNonNull(requestSender, "requestSender");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    /**
     * Requests the configured pack for one online viewer.
     *
     * @param player viewer receiving the pack
     * @return one shared stage for this viewer's active handshake
     */
    public CompletionStage<HandshakeResult> request(Player player) {
        Objects.requireNonNull(player, "player");
        if (closed.get()) {
            return failed(new IllegalStateException("Resource-pack service is closed"));
        }
        if (!player.isOnline()) {
            return failed(new IllegalStateException("Resource-pack viewer is offline"));
        }

        UUID viewerId = player.getUniqueId();
        Player loadedConnection = loadedConnections.get(viewerId);
        if (loadedConnection == player) {
            return CompletableFuture.completedFuture(success(viewerId));
        }
        if (loadedConnection != null) {
            loadedConnections.remove(viewerId, loadedConnection);
        }

        synchronized (lifecycleLock) {
            if (closed.get()) {
                return failed(new IllegalStateException("Resource-pack service is closed"));
            }
            PendingHandshake created = new PendingHandshake(player);
            PendingHandshake existing = pending.putIfAbsent(viewerId, created);
            if (existing != null) {
                if (existing.player != player) {
                    fail(existing, new CancellationException("Resource-pack connection changed"), State.CLOSED);
                    pending.put(viewerId, created);
                } else {
                    return existing.future;
                }
            }

            created.timeout = scheduler.schedule(
                    () -> fail(created, new TimeoutException("Resource-pack handshake timed out"), State.FAILED),
                    configuration.handshakeTimeout().toNanos(),
                    TimeUnit.NANOSECONDS);

            ResourcePackInfo packInfo = ResourcePackInfo.resourcePackInfo(
                    configuration.packId(),
                    configuration.uri(),
                    configuration.sha1());
            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(packInfo)
                    .required(true)
                    .replace(false)
                    .prompt(configuration.prompt())
                    .callback((packId, status, audience) -> handleStatus(created, packId, status, audience))
                    .build();

            try {
                requestSender.send(player, request);
            } catch (RuntimeException failure) {
                fail(created, failure, State.FAILED);
            }
            return created.future;
        }
    }

    /**
     * Returns whether the current configured pack was successfully applied.
     *
     * @param viewerId viewer to inspect
     * @return true when that viewer has completed the successful handshake
     */
    public boolean isLoaded(UUID viewerId) {
        return viewerId != null && loadedConnections.containsKey(viewerId);
    }

    /** Returns whether the pack is ready on this exact live connection. */
    public boolean isLoaded(Player player) {
        return player != null && loadedConnections.get(player.getUniqueId()) == player;
    }

    /**
     * Invalidates both pending and successful readiness for the supplied viewer.
     *
     * @param viewerId viewer whose pending handshake should be cancelled
     */
    public void cancel(UUID viewerId) {
        invalidate(viewerId);
    }

    /** Invalidates all pack readiness associated with one exact connection. */
    public void invalidate(Player player) {
        if (player == null) {
            return;
        }
        UUID viewerId = player.getUniqueId();
        loadedConnections.remove(viewerId, player);
        PendingHandshake handshake = pending.get(viewerId);
        if (handshake != null && handshake.player == player) {
            fail(handshake, new CancellationException("Resource-pack connection invalidated"), State.CLOSED);
        }
    }

    /** Invalidates readiness and pending work for a viewer UUID. */
    public void invalidate(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        loadedConnections.remove(viewerId);
        PendingHandshake handshake = pending.get(viewerId);
        if (handshake != null) {
            fail(handshake, new CancellationException("Resource-pack handshake cancelled"), State.CLOSED);
        }
    }

    /**
     * Closes the service exactly once and fails all pending handshakes closed.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (PendingHandshake handshake : pending.values()) {
                fail(handshake, new CancellationException("Resource-pack service closed"), State.CLOSED);
            }
            loadedConnections.clear();
            if (ownsScheduler) {
                scheduler.shutdownNow();
            }
        }
    }

    private void handleStatus(
            PendingHandshake handshake,
            UUID packId,
            ResourcePackStatus status,
            Audience audience) {
        if (!configuration.packId().equals(packId)
                || audience == null
                || !audience.equals(handshake.player)
                || status == null) {
            return;
        }

        synchronized (handshake) {
            if (handshake.terminal || closed.get()) {
                return;
            }
            switch (status) {
                case ACCEPTED -> handshake.state = State.ACCEPTED;
                case DOWNLOADED -> handshake.state = State.DOWNLOADED;
                case SUCCESSFULLY_LOADED -> {
                    handshake.state = State.READY;
                    handshake.terminal = true;
                    loadedConnections.put(handshake.viewerId, handshake.player);
                    removePending(handshake);
                    cancelTimeout(handshake);
                    dispatch(() -> handshake.future.complete(success(handshake.viewerId)));
                }
                case DECLINED, INVALID_URL, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED -> {
                    handshake.state = State.FAILED;
                    handshake.terminal = true;
                    loadedConnections.remove(handshake.viewerId, handshake.player);
                    removePending(handshake);
                    cancelTimeout(handshake);
                    HandshakeException failure = new HandshakeException(handshake.viewerId, status);
                    dispatch(() -> handshake.future.completeExceptionally(failure));
                }
            }
        }
    }

    private void fail(PendingHandshake handshake, Throwable failure, State terminalState) {
        synchronized (handshake) {
            if (handshake.terminal) {
                return;
            }
            handshake.state = terminalState;
            handshake.terminal = true;
            loadedConnections.remove(handshake.viewerId, handshake.player);
            removePending(handshake);
            cancelTimeout(handshake);
            dispatch(() -> handshake.future.completeExceptionally(failure));
        }
    }

    private void removePending(PendingHandshake handshake) {
        pending.remove(handshake.viewerId, handshake);
    }

    private void cancelTimeout(PendingHandshake handshake) {
        ScheduledFuture<?> timeout = handshake.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private HandshakeResult success(UUID viewerId) {
        return new HandshakeResult(
                viewerId,
                configuration.packId(),
                configuration.uri(),
                ResourcePackStatus.SUCCESSFULLY_LOADED);
    }

    private void dispatch(Runnable action) {
        try {
            completionExecutor.execute(action);
        } catch (RuntimeException schedulingFailure) {
            action.run();
        }
    }

    private static <T> CompletionStage<T> failed(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static Executor paperCompletionExecutor(JavaPlugin plugin) {
        JavaPlugin checkedPlugin = Objects.requireNonNull(plugin, "plugin");
        return command -> checkedPlugin.getServer().getScheduler().runTask(checkedPlugin, command);
    }
}
