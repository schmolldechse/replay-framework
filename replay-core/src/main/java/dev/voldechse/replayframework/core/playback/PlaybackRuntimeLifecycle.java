package dev.voldechse.replayframework.core.playback;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Internal lifecycle boundary used by the Paper runtime to close viewer sessions. */
public final class PlaybackRuntimeLifecycle implements AutoCloseable {
    private final DefaultPlaybackService service;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> shutdown =
            new AtomicReference<>();

    PlaybackRuntimeLifecycle(DefaultPlaybackService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** Closes all viewer timelines exactly once and returns their completion stage. */
    public CompletionStage<Void> shutdownForRuntime() {
        CompletableFuture<Void> existing = shutdown.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Void> created = new CompletableFuture<>();
        if (!shutdown.compareAndSet(null, created)) {
            return shutdown.get();
        }
        closed.set(true);
        try {
            service.shutdown().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    created.complete(null);
                } else {
                    created.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            created.completeExceptionally(failure);
        }
        return created;
    }

    @Override
    public void close() {
        shutdownForRuntime();
    }
}
