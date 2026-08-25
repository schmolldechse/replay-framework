package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.core.capture.CaptureRouter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns installation and removal of the single adapter capture route. */
public final class RecordingRuntimeLifecycle implements AutoCloseable {
    private final ReplayAdapter adapter;
    private final CaptureRouter captureRouter;
    private final RecordingCoordinator coordinator;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> serverShutdown =
            new AtomicReference<>();

    public RecordingRuntimeLifecycle(
            ReplayAdapter adapter,
            CaptureRouter captureRouter,
            RecordingCoordinator coordinator) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.captureRouter = Objects.requireNonNull(captureRouter, "captureRouter");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Installs the adapter hook exactly once. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("recording runtime lifecycle is closed");
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            adapter.captureBridge().install(
                    captureRouter,
                    coordinator::onAdapterFailure);
        } catch (Throwable failure) {
            started.set(false);
            throw failure;
        }
    }

    /** Starts clean server-shutdown finalization exactly once. */
    public CompletionStage<Void> shutdownForServer() {
        CompletableFuture<Void> existing = serverShutdown.get();
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Void> created = new CompletableFuture<>();
        if (!serverShutdown.compareAndSet(null, created)) {
            return serverShutdown.get();
        }
        closed.set(true);
        CompletionStage<Void> shutdownStage;
        try {
            shutdownStage = coordinator.shutdownForServer();
        } catch (Throwable failure) {
            shutdownStage = CompletableFuture.failedFuture(failure);
        }
        uninstallCaptureRoute();
        shutdownStage.whenComplete((ignored, failure) -> {
            if (failure != null) {
                created.completeExceptionally(failure);
            } else {
                created.complete(null);
            }
        });
        return created;
    }

    /** Fails remaining recordings after the runtime shutdown budget expires. */
    public CompletionStage<Void> failOutstandingForShutdownTimeout() {
        closed.set(true);
        try {
            return coordinator.failOutstandingForShutdownTimeout();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void close() {
        shutdownForServer();
    }

    private void uninstallCaptureRoute() {
        if (!started.get()) {
            captureRouter.close();
            return;
        }
        try {
            adapter.captureBridge().uninstall();
        } finally {
            captureRouter.close();
        }
    }
}
