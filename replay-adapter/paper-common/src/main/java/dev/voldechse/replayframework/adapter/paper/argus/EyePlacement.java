package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.api.recording.CuboidRegion;
import java.util.List;

/** Decides where eyes stand so that a region is observed without holes. */
public interface EyePlacement {

    /**
     * Plans the eyes of one region.
     *
     * @param region region to cover
     * @param trackingRangeBlocks horizontal distance within which an eye is
     *                            guaranteed to receive every entity
     * @throws IllegalArgumentException when the region exceeds the eye budget
     */
    List<EyeSpec> plan(CuboidRegion region, int trackingRangeBlocks);
}
