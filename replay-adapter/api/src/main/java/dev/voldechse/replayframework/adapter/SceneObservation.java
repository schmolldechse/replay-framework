package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.UUID;

/**
 * Adapter boundary for observing a recording scope independently of the
 * players that happen to be connected.
 *
 * <p>An adapter that supports it places virtual observers for a scope and
 * publishes what they see through its {@link CaptureBridge} with
 * {@link CaptureOrigin#OBSERVER} and the identity of the watch. The core only
 * opens and closes a watch per recording; it never sees an observer.</p>
 *
 * <p>The observers define what such a recording contains: everything they
 * see belongs to it. Filtering their packets one by one would tear relative
 * movement apart.</p>
 */
public interface SceneObservation {

    /**
     * Reports whether a watch on this scope delivers the scene. When it does,
     * the recording must not take scene packets from player connections.
     */
    boolean observes(RecordingScope scope);

    /**
     * Returns the scope the observers of {@code scope} actually see: the
     * requested regions plus the margin of their view. Checkpoints of an
     * observed recording must cover it, because everything the observers see
     * is part of the recording.
     *
     * @throws IllegalArgumentException when the scope cannot be covered
     */
    default RecordingScope coverage(RecordingScope scope) {
        return scope;
    }

    /**
     * Starts observing a scope. May be called from any thread.
     *
     * @param observationId identity every packet of this watch carries in
     *                      {@link CaptureContext#observation()}
     * @throws IllegalArgumentException when the scope cannot be covered
     */
    Watch watch(RecordingScope scope, UUID observationId);

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
            public Watch watch(RecordingScope scope, UUID observationId) {
                return () -> { };
            }
        };
    }
}
