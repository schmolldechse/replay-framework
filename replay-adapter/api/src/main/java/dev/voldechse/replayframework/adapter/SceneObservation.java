package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.api.recording.ObserverOptions;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.Objects;
import java.util.Set;
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
     * Returns the scope a watch on this request would see if it opened now.
     * The initial checkpoint of an observed recording covers it. May be
     * called from any thread.
     *
     * @throws IllegalArgumentException when the scope exceeds a budget
     */
    default RecordingScope coverage(Request request) {
        return Objects.requireNonNull(request, "request").scope();
    }

    /**
     * Starts observing a scope. May be called from any thread.
     *
     * @throws IllegalArgumentException when the scope exceeds a budget
     */
    Watch watch(Request request);

    /**
     * One recording's request for observers.
     *
     * @param observationId identity every packet of the watch carries in
     *                      {@link CaptureContext#observation()}
     * @param scope what the recording covers
     * @param participants players the recording follows; empty follows all
     *                     players in the scope
     * @param options grace period and budgets of the observers
     */
    record Request(
            UUID observationId,
            RecordingScope scope,
            Set<UUID> participants,
            ObserverOptions options) {

        public Request {
            Objects.requireNonNull(observationId, "observationId");
            Objects.requireNonNull(scope, "scope");
            participants = Set.copyOf(Objects.requireNonNull(participants, "participants"));
            Objects.requireNonNull(options, "options");
        }
    }

    /** One recording's claim on its observers; closing is idempotent. */
    interface Watch extends AutoCloseable {

        /**
         * Returns what the observers see right now: the chunks of every
         * open observer. Checkpoints of the recording cover it, so a seek
         * restores a scene the following stream fits. May be called from any
         * thread.
         */
        RecordingScope coverage();

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
            public Watch watch(Request request) {
                RecordingScope scope = Objects.requireNonNull(request, "request").scope();
                return new Watch() {
                    @Override
                    public RecordingScope coverage() {
                        return scope;
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }
}
