package dev.voldechse.replayframework.core.playback;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.api.playback.PlaybackService;
import dev.voldechse.replayframework.core.artifact.ReplayArtifactReader;
import dev.voldechse.replayframework.core.diagnostics.ReplayDiagnostics;
import dev.voldechse.replayframework.core.event.ReplayEventDispatcher;
import dev.voldechse.replayframework.core.playback.cache.SegmentCache;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import dev.voldechse.replayframework.format.ReplayCheckpointReader;
import dev.voldechse.replayframework.format.ReplayIndexReader;
import dev.voldechse.replayframework.format.ReplaySegmentReader;
import java.util.concurrent.Executor;

/** Internal Guice composition for viewer-specific playback sessions. */
public final class PlaybackRuntimeModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(PlaybackCoordinator.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    PlaybackEventDispatcher providePlaybackEventDispatcher(ReplayEventDispatcher events) {
        return new PlaybackEventDispatcher(events);
    }

    @Provides
    @Singleton
    DefaultPlaybackService providePlaybackService(
            ReplayRepository repository,
            ReplayArtifactReader artifactReader,
            ReplayAdapter adapter,
            SegmentCache sharedSegmentCache,
            ReplayIndexReader indexReader,
            ReplayCheckpointReader checkpointReader,
            ReplaySegmentReader segmentReader,
            Executor ioExecutor,
            PlaybackCoordinator coordinator,
            PlaybackEventDispatcher eventDispatcher,
            ReplayDiagnostics diagnostics) {
        return new DefaultPlaybackService(
                repository,
                artifactReader,
                adapter,
                sharedSegmentCache,
                indexReader,
                checkpointReader,
                segmentReader,
                ioExecutor,
                ioExecutor,
                coordinator,
                eventDispatcher,
                diagnostics);
    }

    @Provides
    @Singleton
    PlaybackRuntimeLifecycle providePlaybackLifecycle(DefaultPlaybackService service) {
        return new PlaybackRuntimeLifecycle(service);
    }

    @Provides
    @Singleton
    PlaybackService providePublicPlaybackService(DefaultPlaybackService service) {
        return service;
    }
}
