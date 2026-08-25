package dev.voldechse.replayframework.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26ReplayAdapter;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.Paper26CaptureBridge;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26CheckpointEncoder;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26NativePacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26SnapshotProvider;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26PlaybackBridge;
import dev.voldechse.replayframework.api.ReplayFramework;
import dev.voldechse.replayframework.api.ReplayFrameworkProvider;
import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataService;
import dev.voldechse.replayframework.api.playback.PlaybackService;
import dev.voldechse.replayframework.api.recording.RecordingService;
import dev.voldechse.replayframework.api.replay.ReplayService;
import dev.voldechse.replayframework.core.event.ReplayEventDispatcher;
import dev.voldechse.replayframework.core.playback.PlaybackRuntimeLifecycle;
import dev.voldechse.replayframework.core.playback.cache.DiskSegmentCache;
import dev.voldechse.replayframework.core.recording.RecordingLeaseManager;
import dev.voldechse.replayframework.core.recording.RecordingRuntimeLifecycle;
import dev.voldechse.replayframework.database.postgresql.HibernateSessionFactory;
import dev.voldechse.replayframework.runtime.config.ReplayConfigurationLoader;
import dev.voldechse.replayframework.runtime.config.ReplayRuntimeConfiguration;
import dev.voldechse.replayframework.runtime.di.ReplayRuntimeModule;
import dev.voldechse.replayframework.storage.ReplayStorage;
import dev.voldechse.replayframework.storage.s3.S3ReplayStorage;
import dev.voldechse.replayframework.storage.sftp.SftpReplayStorage;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper lifecycle owner for the internal runtime composition. */
public final class ReplayPaperPlugin extends JavaPlugin {
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean serviceRegistered = new AtomicBoolean();
    private ExecutorService bootstrapExecutor;
    private CompletableFuture<RuntimeState> bootstrapFuture;
    private volatile PaperCheckpointResources checkpointResources;
    private volatile RuntimeState runtimeState;

    @Override
    public void onEnable() {
        final Paper26ReplayAdapter adapter;
        try {
            // Paper-bound protocol and world ports are created before the
            // bootstrap worker starts so no NMS factory runs off-thread.
            adapter = createPaperAdapter();
        } catch (Throwable failure) {
            closeCheckpointResources();
            getLogger().severe("Replay Framework adapter bootstrap failed: "
                    + failure.getClass().getSimpleName());
            disableAfterBootstrapFailure();
            return;
        }
        bootstrapExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("replay-bootstrap-", 0).factory());
        bootstrapFuture = CompletableFuture.supplyAsync(
                () -> createRuntimeState(adapter),
                bootstrapExecutor);
        bootstrapFuture.whenComplete((state, failure) -> getServer().getScheduler().runTask(
                this,
                () -> completeBootstrap(state, failure)));
    }

    @Override
    public void onDisable() {
        shutdownRequested.set(true);
        ReplayFrameworkProvider.clear();
        unregisterService();

        RuntimeState current = runtimeState;
        if (current != null) {
            runtimeState = null;
            // Paper may close the plugin classloader immediately after this
            // callback returns, so the owned graph must be resolved and
            // closed while the loader is still available.
            current.close(getLogger()::warning);
        }

        CompletableFuture<RuntimeState> pending = bootstrapFuture;
        if (pending != null && !pending.isDone()) {
            pending.whenComplete((state, failure) -> {
                if (state != null) {
                    closeAsync(state);
                } else {
                    closeCheckpointResources();
                }
            });
            pending.cancel(true);
        } else if (current == null) {
            closeCheckpointResources();
        }

        ExecutorService executor = bootstrapExecutor;
        if (executor != null) {
            executor.shutdown();
        }
    }

    private RuntimeState createRuntimeState(ReplayAdapter adapter) {
        Gson gson = new GsonBuilder().create();
        ReplayRuntimeConfiguration configuration = new ReplayConfigurationLoader(gson)
                .load(getDataFolder().toPath());
        ReplayRuntimeModule module = new ReplayRuntimeModule(
                configuration,
                adapter,
                gson,
                message -> getLogger().warning(message));
        try {
            Injector injector = Guice.createInjector(module);
            return RuntimeState.from(injector, module, this::closeCheckpointResources);
        } catch (Throwable failure) {
            module.close();
            throw failure;
        }
    }

    private Paper26ReplayAdapter createPaperAdapter() {
        PaperConnectionAccessor accessor = new PaperConnectionAccessor(this);
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();
        Paper26CaptureBridge captureBridge = new Paper26CaptureBridge(accessor, registry);
        Paper26SnapshotProvider snapshotProvider = new Paper26SnapshotProvider(this);
        Paper26NativePacketCodec nativePacketCodec = null;
        ExecutorService checkpointExecutor = null;
        try {
            nativePacketCodec = Paper26NativePacketCodec.create(registry);
            checkpointExecutor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(128),
                    Thread.ofPlatform().name("replay-checkpoint-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            Paper26CheckpointEncoder checkpointEncoder = new Paper26CheckpointEncoder(
                    registry,
                    snapshotProvider,
                    nativePacketCodec,
                    checkpointExecutor);
            AtomicReference<Paper26ReplayAdapter> adapterReference = new AtomicReference<>();
            Paper26ReplayAdapter adapter = new Paper26ReplayAdapter(
                    captureBridge,
                    checkpointEncoder,
                    player -> {
                        Paper26ReplayAdapter current = adapterReference.get();
                        if (current == null) {
                            throw new IllegalStateException("Paper adapter is not initialized");
                        }
                        return new Paper26PlaybackBridge(
                                accessor,
                                player,
                                current.descriptor(),
                                current.packetRegistry(),
                                failure -> getLogger().warning(
                                        "Paper playback bridge failed: "
                                                + failure.getClass().getSimpleName()));
                    });
            adapterReference.set(adapter);
            checkpointResources = new PaperCheckpointResources(
                    snapshotProvider,
                    nativePacketCodec,
                    checkpointExecutor);
            return adapter;
        } catch (Throwable failure) {
            snapshotProvider.close();
            if (nativePacketCodec != null) {
                nativePacketCodec.close();
            }
            if (checkpointExecutor != null) {
                checkpointExecutor.shutdownNow();
            }
            throw failure;
        }
    }

    private void completeBootstrap(RuntimeState state, Throwable failure) {
        if (shutdownRequested.get()) {
            if (state != null) {
                closeAsync(state);
            }
            return;
        }
        if (failure != null) {
            Throwable cause = unwrap(failure);
            closeCheckpointResources();
            getLogger().severe("Replay Framework bootstrap failed: "
                    + safeFailureSummary(cause));
            disableAfterBootstrapFailure();
            return;
        }
        try {
            RecordingRuntimeLifecycle recording = state.injector()
                    .getInstance(RecordingRuntimeLifecycle.class);
            recording.start();
            DefaultReplayFramework framework = new DefaultReplayFramework(
                    state.injector().getInstance(RecordingService.class),
                    state.injector().getInstance(PlaybackService.class),
                    state.injector().getInstance(ReplayService.class),
                    state.injector().getInstance(ReplayMetadataService.class),
                    state.injector().getInstance(ReplayEventPublisher.class),
                    () -> closeAsync(state));
            state.installFramework(framework);
            ReplayFrameworkProvider.install(framework);
            getServer().getServicesManager().register(
                    ReplayFramework.class,
                    framework,
                    this,
                    ServicePriority.Normal);
            serviceRegistered.set(true);
            runtimeState = state;
            getLogger().info("Replay Framework enabled on Java "
                    + Runtime.version().feature());
        } catch (Throwable bootstrapFailure) {
            ReplayFrameworkProvider.clear();
            state.close(getLogger()::warning);
            getLogger().severe("Replay Framework publication failed: "
                    + safeFailureSummary(bootstrapFailure));
            disableAfterBootstrapFailure();
        }
    }

    private void disableAfterBootstrapFailure() {
        getServer().getScheduler().runTask(this, () ->
                getServer().getPluginManager().disablePlugin(this));
    }

    private void unregisterService() {
        if (serviceRegistered.compareAndSet(true, false)) {
            RuntimeState current = runtimeState;
            if (current != null && current.framework() != null) {
                getServer().getServicesManager().unregister(
                        ReplayFramework.class,
                        current.framework());
            }
        }
    }

    private void closeAsync(RuntimeState state) {
        ExecutorService executor = bootstrapExecutor;
        if (executor == null || executor.isShutdown()) {
            state.close(getLogger()::warning);
            return;
        }
        try {
            executor.execute(() -> state.close(getLogger()::warning));
        } catch (RuntimeException rejected) {
            state.close(getLogger()::warning);
        }
    }

    private void closeCheckpointResources() {
        PaperCheckpointResources resources = checkpointResources;
        if (resources != null) {
            resources.close();
            checkpointResources = null;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) {
            return failure.getCause() == null ? failure : failure.getCause();
        }
        return failure;
    }

    private static String safeFailureSummary(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String sanitized = message
                .replaceAll("(?i)(password|token|secret)(\\s*[=:]\\s*)[^,\\]\\r\\n]+",
                        "$1$2<redacted>")
                .replaceAll("\\r?\\n", " | ");
        if (sanitized.length() > 2000) {
            sanitized = sanitized.substring(0, 2000) + "…";
        }
        return failure.getClass().getSimpleName() + ": " + sanitized;
    }

    private static final class RuntimeState {
        private final Injector injector;
        private final ReplayRuntimeModule module;
        private final Runnable checkpointResourcesCloser;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile DefaultReplayFramework framework;

        private RuntimeState(
                Injector injector,
                ReplayRuntimeModule module,
                Runnable checkpointResourcesCloser) {
            this.injector = Objects.requireNonNull(injector, "injector");
            this.module = Objects.requireNonNull(module, "module");
            this.checkpointResourcesCloser = Objects.requireNonNull(
                    checkpointResourcesCloser,
                    "checkpointResourcesCloser");
        }

        private static RuntimeState from(
                Injector injector,
                ReplayRuntimeModule module,
                Runnable checkpointResourcesCloser) {
            return new RuntimeState(injector, module, checkpointResourcesCloser);
        }

        private Injector injector() {
            return injector;
        }

        private DefaultReplayFramework framework() {
            return framework;
        }

        private void installFramework(DefaultReplayFramework framework) {
            this.framework = Objects.requireNonNull(framework, "framework");
        }

        private void close(Consumer<String> logger) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // Session owners close first; shared executors and external clients
            // remain alive until no component can submit new work.
            closeStep("playback", () -> injector.getInstance(PlaybackRuntimeLifecycle.class).close(), logger);
            closeStep("recording", () -> injector.getInstance(RecordingRuntimeLifecycle.class).close(), logger);
            closeStep("leases", () -> injector.getInstance(RecordingLeaseManager.class).close(), logger);
            closeStep("segment cache", () -> injector.getInstance(DiskSegmentCache.class).close(), logger);
            closeStep("events", () -> injector.getInstance(ReplayEventDispatcher.class).close(), logger);
            closeStep("paper checkpoint resources", checkpointResourcesCloser, logger);
            closeStep("storage", () -> closeStorage(injector.getInstance(ReplayStorage.class)), logger);
            closeStep("database", () -> injector.getInstance(HibernateSessionFactory.class).close(), logger);
            closeStep("recording scheduler", () -> injector.getInstance(
                    Key.get(ScheduledExecutorService.class,
                            Names.named("replay-recording-scheduler"))).shutdown(), logger);
            closeStep("runtime executor", module::close, logger);
        }

        private static void closeStorage(ReplayStorage storage) {
            if (storage instanceof S3ReplayStorage s3) {
                s3.close();
            } else if (storage instanceof SftpReplayStorage sftp) {
                sftp.close().toCompletableFuture().join();
            }
        }

        private static void closeStep(String name, Runnable action, Consumer<String> logger) {
            try {
                action.run();
            } catch (Throwable failure) {
                logger.accept("Replay Framework " + name + " shutdown failed: "
                        + safeFailureSummary(failure));
            }
        }
    }

    private static final class PaperCheckpointResources implements AutoCloseable {
        private final Paper26SnapshotProvider snapshotProvider;
        private final Paper26NativePacketCodec nativePacketCodec;
        private final ExecutorService checkpointExecutor;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PaperCheckpointResources(
                Paper26SnapshotProvider snapshotProvider,
                Paper26NativePacketCodec nativePacketCodec,
                ExecutorService checkpointExecutor) {
            this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider");
            this.nativePacketCodec = Objects.requireNonNull(nativePacketCodec, "nativePacketCodec");
            this.checkpointExecutor = Objects.requireNonNull(checkpointExecutor, "checkpointExecutor");
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            snapshotProvider.close();
            nativePacketCodec.close();
            checkpointExecutor.shutdownNow();
        }
    }
}
