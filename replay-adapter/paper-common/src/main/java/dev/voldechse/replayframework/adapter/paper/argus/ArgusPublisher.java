package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.UUID;

/** Receives the merged scene of a world, one native packet at a time. */
@FunctionalInterface
public interface ArgusPublisher {

    /**
     * Publishes one scene packet on the server thread.
     *
     * @param sceneId stable identity of the scene, usable wherever
     *                packet-owned capture state is keyed by a connection
     * @param world namespaced world key
     * @param packet native packet object
     * @param anchor position of the observation that produced the packet,
     *               for packets that carry no position of their own
     */
    void publish(UUID sceneId, String world, Object packet, BlockPosition anchor);
}
