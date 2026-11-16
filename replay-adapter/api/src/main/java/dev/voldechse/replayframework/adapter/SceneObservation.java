package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.api.recording.RecordingScope;

/**
 * Adapter boundary for observing a recording scope independently of the
 * players that happen to be connected.
 *
 * <p>An adapter that supports it places virtual observers for a scope and
 * publishes what they see through its {@link CaptureBridge} with
 * {@link CaptureOrigin#OBSERVER}. The core only opens and closes a watch per
 * recording; it never sees an observer.</p>
 */
public interface SceneObservation {

    /**
     * Reports whether a watch on this scope delivers the scene. When it does,
     * the recording must not take scene packets from player connections.
     */
    boolean observes(RecordingScope scope);

    /**
     * Starts observing a scope. May be called from any thread.
     *
     * @throws IllegalArgumentException when the scope cannot be covered
     */
    Watch watch(RecordingScope scope);

    /** One recording's claim on its observers; closing is idempotent. */
    interface Watch extends AutoCloseable {
        @Override
        void close();
    }

    /** Observation of an adapter that only captures player connections. */
    static SceneObservation none() {
        return new SceneObservation() {
            @Override
            public boolean observes(RecordingScope scope) {
                return false;
            }

            @Override
            public Watch watch(RecordingScope scope) {
                return () -> { };
            }
        };
    }
}
