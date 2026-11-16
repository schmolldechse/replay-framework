package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.api.recording.CuboidRegion;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Covers a region with a rectangular lattice of static eyes.
 *
 * <p>The region is divided into equal cells with one eye at each center. A
 * cell is small enough that its corners, the points farthest from the eye,
 * are still inside the entity tracking range and inside the chunks the eye
 * receives. Each eye then gets the smallest view distance that reaches its
 * cell corners, so the lattice loads little beyond the region itself.</p>
 */
public final class LatticeEyePlacement implements EyePlacement {

    private static final int CHUNK_BLOCKS = 16;

    private final int maxEyes;
    private final int maxViewDistance;

    /**
     * @param maxEyes eye budget of one region
     * @param maxViewDistance largest chunk radius a single eye may load
     */
    public LatticeEyePlacement(int maxEyes, int maxViewDistance) {
        if (maxEyes < 1) {
            throw new IllegalArgumentException("maxEyes must be positive");
        }
        if (maxViewDistance < 3 || maxViewDistance > 32) {
            throw new IllegalArgumentException("maxViewDistance must be between 3 and 32");
        }
        this.maxEyes = maxEyes;
        this.maxViewDistance = maxViewDistance;
    }

    @Override
    public List<EyeSpec> plan(CuboidRegion region, int trackingRangeBlocks) {
        Objects.requireNonNull(region, "region");
        if (trackingRangeBlocks < CHUNK_BLOCKS) {
            throw new IllegalArgumentException("trackingRangeBlocks must be at least one chunk");
        }
        double width = (double) region.max().x() - region.min().x() + 1.0;
        double depth = (double) region.max().z() - region.min().z() + 1.0;

        // One chunk of the view distance is kept as margin, because an eye
        // does not stand on a chunk center and the sent area is round.
        double reach = Math.min(trackingRangeBlocks, (maxViewDistance - 1) * (double) CHUNK_BLOCKS);
        // A square cell of this side has its corners exactly at the reach.
        double maxCellSide = reach * Math.sqrt(2.0);

        long columns = (long) Math.ceil(width / maxCellSide);
        long rows = (long) Math.ceil(depth / maxCellSide);
        if (columns * rows > maxEyes) {
            throw new IllegalArgumentException(
                    "region needs " + (columns * rows) + " eyes but the budget is " + maxEyes);
        }
        double cellWidth = width / columns;
        double cellDepth = depth / rows;
        double cornerDistance = Math.hypot(cellWidth, cellDepth) / 2.0;
        int viewDistance = Math.max(2, (int) Math.ceil(cornerDistance / CHUNK_BLOCKS) + 1);
        double y = (region.min().y() + (double) region.max().y()) / 2.0;

        List<EyeSpec> eyes = new ArrayList<>((int) (columns * rows));
        for (int column = 0; column < columns; column++) {
            for (int row = 0; row < rows; row++) {
                eyes.add(new EyeSpec(
                        region.world().asString(),
                        region.min().x() + (column + 0.5) * cellWidth,
                        y,
                        region.min().z() + (row + 0.5) * cellDepth,
                        viewDistance));
            }
        }
        return List.copyOf(eyes);
    }
}
