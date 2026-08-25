package dev.voldechse.replayframework.runtime;

import dev.voldechse.replayframework.api.ReplayFramework;
import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataService;
import dev.voldechse.replayframework.api.playback.PlaybackService;
import dev.voldechse.replayframework.api.recording.RecordingService;
import dev.voldechse.replayframework.api.replay.ReplayService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned implementation of the public framework facade. */
public final class DefaultReplayFramework implements ReplayFramework, AutoCloseable {
    private final RecordingService recordings;
    private final PlaybackService playbacks;
    private final ReplayService replays;
    private final ReplayMetadataService metadata;
    private final ReplayEventPublisher events;
    private final Runnable shutdown;
    private final AtomicBoolean closed = new AtomicBoolean();

    DefaultReplayFramework(
            RecordingService recordings,
            PlaybackService playbacks,
            ReplayService replays,
            ReplayMetadataService metadata,
            ReplayEventPublisher events,
            Runnable shutdown) {
        this.recordings = Objects.requireNonNull(recordings, "recordings");
        this.playbacks = Objects.requireNonNull(playbacks, "playbacks");
        this.replays = Objects.requireNonNull(replays, "replays");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.events = Objects.requireNonNull(events, "events");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
    }

    @Override
    public RecordingService recordings() {
        return recordings;
    }

    @Override
    public PlaybackService playbacks() {
        return playbacks;
    }

    @Override
    public ReplayService replays() {
        return replays;
    }

    @Override
    public ReplayMetadataService metadata() {
        return metadata;
    }

    @Override
    public ReplayEventPublisher events() {
        return events;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            shutdown.run();
        }
    }
}
