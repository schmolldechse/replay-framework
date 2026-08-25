package dev.voldechse.replayframework.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26ReplayAdapter;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.Paper26CaptureBridge;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26CheckpointEncoder;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private volatile RuntimeState runtimeState;

    @Override
    public void onEnable() {
        bootstrapExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("replay-bootstrap-", 0).factory());
        bootstrapFuture = CompletableFuture.supplyAsync(this::createRuntimeState, bootstrapExecutor);
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
            closeAsync(current);
        }

        CompletableFuture<RuntimeState> pending = bootstrapFuture;
        if (pending != null && !pending.isDone()) {
            pending.whenComplete((state, failure) -> {
                if (state != null) {
                    closeAsync(state);
                }
            });
            pending.cancel(true);
        }

        ExecutorService executor = bootstrapExecutor;
        if (executor != null) {
            executor.shutdown();
        }
    }

    private RuntimeState createRuntimeState() {
        Gson gson = new GsonBuilder().create();
        ReplayRuntimeConfiguration configuration = new ReplayConfigurationLoader(gson)
                .load(getDataFolder().toPath());
        ReplayAdapter adapter = createPaperAdapter();
        ReplayRuntimeModule module = new ReplayRuntimeModule(
                configuration,
                adapter,
                gson,
                message -> getLogger().warning(message));
        try {
            Injector injector = Guice.createInjector(module);
            return RuntimeState.from(injector, module);
        } catch (Throwable failure) {
            module.close();
            throw failure;
        }
    }

    private ReplayAdapter createPaperAdapter() {
        PaperConnectionAccessor accessor = new PaperConnectionAccessor(this);
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();
        Paper26CaptureBridge captureBridge = new Paper26CaptureBridge(accessor, registry);
        // A missing world snapshot must fail the checkpoint operation rather
        // than silently turning a partial state into a playable replay.
        Paper26CheckpointEncoder checkpointEncoder = new Paper26CheckpointEncoder(
                registry,
                request -> CompletableFuture.failedFuture(new IllegalStateException(
                        "Paper 26.2 checkpoint snapshot provider is unavailable")),
                blueprint -> {
                    throw new IllegalStateException(
                            "Paper 26.2 native checkpoint codec is unavailable");
                },
                Runnable::run);
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
        return adapter;
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
            getLogger().severe("Replay Framework bootstrap failed: "
                    + cause.getClass().getSimpleName());
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
                    + bootstrapFailure.getClass().getSimpleName());
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
        executor.execute(() -> state.close(getLogger()::warning));
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) {
            return failure.getCause() == null ? failure : failure.getCause();
        }
        return failure;
    }

    private static final class RuntimeState {
        private final Injector injector;
        private final ReplayRuntimeModule module;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile DefaultReplayFramework framework;

        private RuntimeState(Injector injector, ReplayRuntimeModule module) {
            this.injector = Objects.requireNonNull(injector, "injector");
            this.module = Objects.requireNonNull(module, "module");
        }

        private static RuntimeState from(Injector injector, ReplayRuntimeModule module) {
            return new RuntimeState(injector, module);
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
            closeStep("storage", () -> closeStorage(injector.getInstance(ReplayStorage.class)), logger);
            closeStep("database", () -> injector.getInstance(HibernateSessionFactory.class).close(), logger);
            closeStep("recording scheduler", () -> injector.getInstance(ScheduledExecutorService.class).shutdown(), logger);
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
                        + failure.getClass().getSimpleName());
            }
        }
    }
}
