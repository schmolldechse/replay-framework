package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.ReplayAdapter;
import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The scene watches of the recordings: the adapter's virtual observers that
 * deliver a recording's scene, opened when the recording becomes active and
 * closed when its sink is removed.
 */
final class SceneWatches {
    private final ReplayAdapter adapter;
    private final Map<RecordingSessionId, SceneObservation.Watch> watches = new ConcurrentHashMap<>();

    SceneWatches(ReplayAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    /** Places the adapter's virtual observers when they deliver this scope's scene. */
    void open(DefaultRecordingSession session, RecordingRequest request) {
        SceneObservation observation = adapter.sceneObservation();
        if (observation.observes(request.scope())) {
            // Named before the watch opens, so no observed packet can arrive
            // at a session that does not know its observation yet.
            SceneObservation.Request observationRequest = observationRequest(session.id(), request);
            session.observation(observationRequest.observationId());
            watches.put(session.id(), observation.watch(observationRequest));
        }
    }

    /**
     * The scope a checkpoint must cover. A checkpoint must hold what the
     * stream after it refers to. For an observed recording that is
     * everything its observers see at that moment; observers that follow
     * players move, so it is asked each time.
     */
    RecordingScope checkpointScope(RecordingSessionId sessionId, RecordingRequest request) {
        SceneObservation.Watch watch = watches.get(sessionId);
        if (watch != null) {
            return watch.coverage();
        }
        SceneObservation observation = adapter.sceneObservation();
        return observation.observes(request.scope())
                ? observation.coverage(observationRequest(sessionId, request))
                : request.scope();
    }

    /** Closes the watch of a recording, if it has one. */
    void close(RecordingSessionId sessionId) {
        SceneObservation.Watch watch = watches.remove(sessionId);
        if (watch != null) {
            watch.close();
        }
    }

    /** The observation of a recording is named after its session. */
    private static SceneObservation.Request observationRequest(
            RecordingSessionId sessionId, RecordingRequest request) {
        return new SceneObservation.Request(
                sessionId.value(),
                request.scope(),
                request.participants(),
                request.options().observers());
    }
}
