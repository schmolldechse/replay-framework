package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import java.util.UUID;

/** Receives the merged scene of a watch, one native packet at a time. */
public interface ArgusPublisher {

    /**
     * Publishes one scene packet on the server thread.
     *
     * @param observationId identity of the watch the packet belongs to
     * @param sceneId stable identity of that watch's scene in one world,
     *                usable wherever packet-owned capture state is keyed by
     *                a connection
     * @param world namespaced world key
     * @param packet native packet object
     */
    void publish(UUID observationId, UUID sceneId, String world, Object packet);

    /**
     * Publishes one native packet of a subject's state on the server thread.
     *
     * @param observationId identity of the watch the subject belongs to
     * @param subject the subject
     * @param packet native packet object
     */
    void publishState(UUID observationId, CaptureSubject subject, Object packet);

    /** Reports that a scene ended, so state kept under its identity can be dropped. */
    void sceneClosed(UUID sceneId);
}
