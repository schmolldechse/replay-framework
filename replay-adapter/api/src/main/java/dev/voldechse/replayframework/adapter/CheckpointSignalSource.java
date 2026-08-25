package dev.voldechse.replayframework.adapter;

import java.util.Objects;

/**
 * Adapter-owned source for semantic events that require a fresh checkpoint.
 *
 * <p>The source carries no core or Paper types. Implementations must invoke
 * listeners without blocking the packet or server thread.</p>
 */
public interface CheckpointSignalSource {

    /** Installs a listener; repeating the same installation is harmless. */
    void install(CheckpointSignalListener listener);

    /** Removes a listener; repeating the removal is harmless. */
    void uninstall(CheckpointSignalListener listener);

    /** Returns a source that never emits signals. */
    static CheckpointSignalSource noop() {
        return NoopCheckpointSignalSource.INSTANCE;
    }

    /** Receives one adapter-verified dimension transition. */
    @FunctionalInterface
    interface CheckpointSignalListener {
        void onDimensionChange(long captureTimeNanos, long serverTick);
    }

    /** Optional publishing side of an adapter-local signal source. */
    interface Emitter extends CheckpointSignalSource {
        void emitDimensionChange(long captureTimeNanos, long serverTick);
    }

    /** Shared no-op implementation for adapters without a signal source. */
    final class NoopCheckpointSignalSource implements CheckpointSignalSource {
        private static final NoopCheckpointSignalSource INSTANCE = new NoopCheckpointSignalSource();

        private NoopCheckpointSignalSource() {
        }

        @Override
        public void install(CheckpointSignalListener listener) {
            Objects.requireNonNull(listener, "listener");
        }

        @Override
        public void uninstall(CheckpointSignalListener listener) {
            Objects.requireNonNull(listener, "listener");
        }
    }
}
