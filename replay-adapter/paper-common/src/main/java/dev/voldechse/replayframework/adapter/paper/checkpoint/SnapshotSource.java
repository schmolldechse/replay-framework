package dev.voldechse.replayframework.adapter.paper.checkpoint;

import dev.voldechse.replayframework.adapter.CheckpointEncoder.CheckpointRequest;
import java.util.concurrent.CompletionStage;

/**
 * The version's access to the world for checkpoints: detaches the state of a
 * request's coverage, usually on the server thread, as native packets.
 */
@FunctionalInterface
public interface SnapshotSource {

    /**
     * @param request the checkpoint request
     * @return the detached state; the stage may complete on any thread
     */
    CompletionStage<CheckpointSnapshot> snapshot(CheckpointRequest request);
}
