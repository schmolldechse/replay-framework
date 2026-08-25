package dev.voldechse.replayframework.core.playback;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal lifecycle boundary used by the Paper runtime to close viewer sessions. */
public final class PlaybackRuntimeLifecycle implements AutoCloseable {
    private final DefaultPlaybackService service;
    private final AtomicBoolean closed = new AtomicBoolean();

    PlaybackRuntimeLifecycle(DefaultPlaybackService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            service.shutdown();
        }
    }
}
