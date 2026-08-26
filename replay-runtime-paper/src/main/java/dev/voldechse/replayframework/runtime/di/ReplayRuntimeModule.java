package dev.voldechse.replayframework.runtime.di;

import com.google.gson.Gson;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataService;
import dev.voldechse.replayframework.api.replay.ReplayService;
import dev.voldechse.replayframework.api.replay.ReplayStorageBackend;
import dev.voldechse.replayframework.core.artifact.ArtifactIntegrityVerifier;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactPublisher;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.event.ReplayEventDispatcher;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.core.metadata.DefaultReplayMetadataService;
import dev.voldechse.replayframework.core.playback.PlaybackRuntimeModule;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.core.playback.cache.DiskSegmentCache;
import dev.voldechse.replayframework.core.recording.RecordingRuntimeModule;
import dev.voldechse.replayframework.core.replay.DefaultReplayService;
import dev.voldechse.replayframework.database.postgresql.PostgresModule;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplayIndexReader;
import dev.voldechse.replayframework.format.ReplayManifestCodec;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import dev.voldechse.replayframework.runtime.config.ReplayRuntimeConfiguration;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Main runtime composition root for storage, database, core and adapter ports. */
public final class ReplayRuntimeModule extends AbstractModule implements AutoCloseable {
    private final ReplayRuntimeConfiguration configuration;
    private final ReplayAdapter adapter;
    private final Gson gson;
    private final ExecutorService runtimeExecutor;
    private final ScheduledExecutorService shutdownScheduler;
    private final Consumer<String> logger;

    public ReplayRuntimeModule(
            ReplayRuntimeConfiguration configuration,
            ReplayAdapter adapter,
            Gson gson,
            Consumer<String> logger) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runtimeExecutor = newRuntimeExecutor();
        this.shutdownScheduler = newShutdownScheduler();
    }

    @Override
    protected void configure() {
        bind(ReplayRuntimeConfiguration.class).toInstance(configuration);
        bind(ReplayRuntimeConfiguration.PostgresSettings.class)
                .toInstance(configuration.postgresql());
        bind(ReplayRuntimeConfiguration.StorageSettings.class)
                .toInstance(configuration.storage());
        bind(ReplayRuntimeConfiguration.RecordingSettings.class)
                .toInstance(configuration.recording());
        bind(ReplayRuntimeConfiguration.PlaybackSettings.class)
                .toInstance(configuration.playback());
        bind(Gson.class).toInstance(gson);
        bind(ReplayDiagnostics.class).in(Singleton.class);
        bind(Executor.class).toInstance(runtimeExecutor);
        bind(ReplayAdapter.class).toInstance(adapter);
        bind(ReplayService.class).to(DefaultReplayService.class).in(Singleton.class);
        bind(ReplayMetadataService.class).to(DefaultReplayMetadataService.class).in(Singleton.class);
        bind(ReplayEventPublisher.class).to(ReplayEventDispatcher.class).in(Singleton.class);

        install(new StorageSelectionModule(configuration.storage(), runtimeExecutor));
        install(new PostgresModule(toPostgresConfiguration(), gson));
        install(new RecordingRuntimeModule(
                adapter,
                ReplayStorageBackend.valueOf(configuration.storage().backend().name()),
                configuration.recording().workspaceRoot()));
        install(new PlaybackRuntimeModule());
    }

    @Provides
    @Singleton
    Clock provideClock() {
        return Clock.systemUTC();
    }

    @Provides
    @Singleton
    @Named("replay-shutdown-scheduler")
    ScheduledExecutorService provideShutdownScheduler() {
        return shutdownScheduler;
    }

    @Provides
    @Singleton
    ReplayEventDispatcher provideEventDispatcher() {
        return new ReplayEventDispatcher(runtimeExecutor, logger);
    }

    @Provides
    @Singleton
    ReplayManifestCodec provideManifestCodec(Gson centralGson) {
        return new ReplayManifestCodec(centralGson);
    }

    @Provides
    @Singleton
    ArtifactIntegrityVerifier provideIntegrityVerifier() {
        return new ArtifactIntegrityVerifier();
    }

    @Provides
    @Singleton
    ReplayArtifactReader provideArtifactReader(
            dev.voldechse.replayframework.storage.ReplayStorage storage,
            ReplayManifestCodec manifestCodec,
            ArtifactIntegrityVerifier verifier,
            ReplayDiagnostics diagnostics) {
        return new ReplayArtifactReader(
                storage,
                manifestCodec,
                verifier,
                runtimeExecutor,
                configuration.playback().workDirectory(),
                diagnostics);
    }

    @Provides
    @Singleton
    ReplayArtifactPublisher provideArtifactPublisher(
            dev.voldechse.replayframework.storage.ReplayStorage storage,
            ReplayManifestCodec manifestCodec,
            ArtifactIntegrityVerifier verifier,
            ReplayDiagnostics diagnostics) {
        Path workDirectory = configuration.recording().workspaceRoot()
                .resolve("artifact-publisher")
                .normalize();
        return new ReplayArtifactPublisher(
                storage,
                manifestCodec,
                verifier,
                runtimeExecutor,
                workDirectory,
                diagnostics);
    }

    @Provides
    @Singleton
    DiskSegmentCache provideDiskSegmentCache(
            ReplayArtifactReader reader,
            ReplayDiagnostics diagnostics) {
        return new DiskSegmentCache(
                configuration.playback().cacheRoot(),
                configuration.playback().cacheMaxBytes(),
                reader,
                runtimeExecutor,
                diagnostics);
    }

    @Provides
    @Singleton
    SegmentCache provideSegmentCache(DiskSegmentCache cache) {
        return cache;
    }

    @Provides
    @Singleton
    ReplayIndexReader provideIndexReader() {
        return new ReplayIndexReader();
    }

    @Provides
    @Singleton
    ReplayCheckpointReader provideCheckpointReader() {
        return new ReplayCheckpointReader();
    }

    @Provides
    @Singleton
    ReplaySegmentReader provideSegmentReader() {
        return new ReplaySegmentReader();
    }

    /** Stops the bounded runtime executor; backend-specific resources are closed by the plugin. */
    @Override
    public void close() {
        runtimeExecutor.shutdown();
        shutdownScheduler.shutdown();
    }

    private PostgresModule.Configuration toPostgresConfiguration() {
        ReplayRuntimeConfiguration.PostgresSettings settings = configuration.postgresql();
        return new PostgresModule.Configuration(
                settings.jdbcUrl(),
                settings.username(),
                settings.password(),
                settings.minimumIdle(),
                settings.maximumPoolSize(),
                settings.connectionTimeout(),
                settings.validationTimeout(),
                settings.idleTimeout(),
                settings.maxLifetime(),
                settings.databaseParallelism(),
                settings.databaseQueueCapacity());
    }

    private static ExecutorService newRuntimeExecutor() {
        ThreadFactory factory = Thread.ofPlatform().name("replay-runtime-", 0).factory();
        return new ThreadPoolExecutor(
                4,
                8,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(512),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ScheduledExecutorService newShutdownScheduler() {
        ThreadFactory factory = Thread.ofPlatform().name("replay-shutdown-", 0).factory();
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(factory);
    }
}
