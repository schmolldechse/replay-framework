package dev.voldechse.replayframework.core.recording;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.recording.CapturePolicy;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.api.recording.RecordingService;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactPublisher;
import dev.voldechse.replayframework.core.capture.CaptureRouter;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.core.event.ReplayEventDispatcher;
import dev.voldechse.replayframework.core.port.LeaseRepository;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.ReplayIndexWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import net.kyori.adventure.key.Key;

/** Internal Guice composition for the asynchronous recording pipeline. */
public final class RecordingRuntimeModule extends AbstractModule {
    private final ReplayAdapter adapter;
    private final ReplayStorageBackend storageBackend;
    private final Path workspaceRoot;

    public RecordingRuntimeModule(
            ReplayAdapter adapter,
            ReplayStorageBackend storageBackend,
            Path workspaceRoot) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.storageBackend = Objects.requireNonNull(storageBackend, "storageBackend");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
    }

    @Override
    protected void configure() {
        bind(PacketRegistry.class).toInstance(adapter.packetRegistry());
        bind(RecordingCoordinatorReference.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    RecordingCoordinator.ScopeResolver provideScopeResolver() {
        return request -> java.util.concurrent.CompletableFuture.completedFuture(
                new ResolvedRecordingScope(
                        request.scope(),
                        0L,
                        request.scope().worlds(),
                        request.scope().regions(),
                        CapturePolicy.builder().build(),
                        request.participants(),
                        packet -> acceptsScope(request.scope(), packet)));
    }

    @Provides
    @Singleton
    RecordingCoordinator.RecordingTarget provideRecordingTarget() {
        Path stagingRoot = workspaceRoot.resolve("recordings").normalize();
        Function<dev.voldechse.replayframework.api.id.ReplayId, Path> staging = replayId ->
                prepareStagingDirectory(stagingRoot, replayId);
        return new RecordingCoordinator.RecordingTarget(
                storageBackend,
                dev.voldechse.replayframework.api.id.ReplayId::toString,
                staging);
    }

    private static Path prepareStagingDirectory(
            Path stagingRoot,
            dev.voldechse.replayframework.api.id.ReplayId replayId) {
        Objects.requireNonNull(stagingRoot, "stagingRoot");
        Objects.requireNonNull(replayId, "replayId");
        Path root = stagingRoot.toAbsolutePath().normalize();
        Path directory = root.resolve(replayId.toString()).normalize();
        if (!directory.startsWith(root)) {
            throw new SecurityException("recording workspace escapes its staging root");
        }
        try {
            rejectExistingWorkspaceLink(root, "recording staging root");
            Files.createDirectories(root);
            rejectExistingWorkspaceLink(root, "recording staging root");
            rejectExistingWorkspaceEntry(directory);
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("could not create recording workspace", failure);
        }
        rejectExistingWorkspaceLink(directory, "recording workspace");
        return directory;
    }

    private static void rejectExistingWorkspaceLink(Path path, String description) {
        if (Files.isSymbolicLink(path)) {
            throw new SecurityException(description + " must not be a symbolic link");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(description + " must be a directory");
        }
    }

    private static void rejectExistingWorkspaceEntry(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path))) {
            throw new SecurityException("recording workspace must be a non-link directory");
        }
    }

    @Provides
    @Singleton
    CaptureRouter provideCaptureRouter(
            PacketRegistry registry,
            RecordingCoordinatorReference reference) {
        return new CaptureRouter(
                registry,
                reference::adapterFailure,
                reference::sinkFailure);
    }

    @Provides
    @Singleton
    RecordingLeaseManager provideLeaseManager(
            LeaseRepository repository,
            Clock clock,
            @Named("replay-recording-scheduler") ScheduledExecutorService scheduler,
            Executor callbackExecutor) {
        return new RecordingLeaseManager(repository, clock, scheduler, callbackExecutor);
    }

    @Provides
    @Singleton
    @Named("replay-recording-scheduler")
    ScheduledExecutorService provideRecordingScheduler() {
        ThreadFactory factory = Thread.ofPlatform().name("replay-recording-heartbeat-", 0).factory();
        return Executors.newScheduledThreadPool(1, factory);
    }

    @Provides
    @Singleton
    ReplayFinalizer provideReplayFinalizer(
            ReplayRepository repository,
            ReplayArtifactPublisher artifactPublisher,
            ReplayIndexWriter indexWriter,
            ReplayAdapter replayAdapter,
            RecordingLeaseManager leaseManager,
            Executor executor,
            Clock clock) {
        return new ReplayFinalizer(
                repository,
                artifactPublisher,
                indexWriter,
                replayAdapter,
                leaseManager,
                executor,
                clock);
    }

    @Provides
    @Singleton
    RecordingCoordinator provideRecordingCoordinator(
            ReplayAdapter replayAdapter,
            CaptureRouter captureRouter,
            ReplayRepository repository,
            RecordingCoordinator.ScopeResolver scopeResolver,
            RecordingCoordinator.RecordingTarget target,
            ReplayFinalizer finalizer,
            RecordingLeaseManager leaseManager,
            Executor executor,
            ReplayEventDispatcher events,
            RecordingCoordinatorReference reference,
            ReplayDiagnostics diagnostics) {
        RecordingCoordinator coordinator = new RecordingCoordinator(
                replayAdapter,
                captureRouter,
                repository,
                scopeResolver,
                target,
                finalizer,
                leaseManager,
                executor,
                events::publish,
                diagnostics);
        reference.bind(coordinator);
        return coordinator;
    }

    @Provides
    @Singleton
    RecordingService provideRecordingService(RecordingCoordinator coordinator) {
        return new DefaultRecordingService(coordinator);
    }

    @Provides
    @Singleton
    RecordingRuntimeLifecycle provideLifecycle(
            ReplayAdapter replayAdapter,
            CaptureRouter captureRouter,
            RecordingCoordinator coordinator) {
        return new RecordingRuntimeLifecycle(replayAdapter, captureRouter, coordinator);
    }

    private static boolean acceptsScope(
            RecordingScope requested,
            dev.voldechse.replayframework.core.capture.CapturedPacket packet) {
        if (requested.worlds().isEmpty() && requested.regions().isEmpty()) {
            return true;
        }
        var context = packet.context();
        Key world = context.world().orElse(null);
        if (world == null) {
            return false;
        }
        if (!requested.worlds().isEmpty() && !requested.worlds().contains(world)) {
            return false;
        }
        if (requested.regions().isEmpty()) {
            return true;
        }
        var position = context.position().orElse(null);
        if (position == null) {
            return false;
        }
        return requested.regions().stream().anyMatch(region -> contains(region, world, position));
    }

    private static boolean contains(
            CuboidRegion region,
            Key world,
            dev.voldechse.replayframework.api.recording.BlockPosition position) {
        return region.world().equals(world)
                && position.x() >= region.min().x()
                && position.x() <= region.max().x()
                && position.y() >= region.min().y()
                && position.y() <= region.max().y()
                && position.z() >= region.min().z()
                && position.z() <= region.max().z();
    }

    /** Deferred failure callbacks break the CaptureRouter/coordinator cycle. */
    static final class RecordingCoordinatorReference {
        private volatile RecordingCoordinator coordinator;

        void bind(RecordingCoordinator coordinator) {
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        }

        void adapterFailure(Throwable failure) {
            RecordingCoordinator current = coordinator;
            if (current != null) {
                current.onAdapterFailure(failure);
            }
        }

        void sinkFailure(
                dev.voldechse.replayframework.api.id.RecordingSessionId sessionId,
                Throwable failure) {
            RecordingCoordinator current = coordinator;
            if (current != null) {
                current.onSinkFailure(sessionId, failure);
            }
        }
    }
}
