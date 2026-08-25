package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.core.capture.CaptureRouter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns installation and removal of the single adapter capture route. */
public final class RecordingRuntimeLifecycle implements AutoCloseable {
    private final ReplayAdapter adapter;
    private final CaptureRouter captureRouter;
    private final RecordingCoordinator coordinator;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        coordinator.shutdown();
        try {
            adapter.captureBridge().uninstall();
        } finally {
            captureRouter.close();
        }
    }
}
