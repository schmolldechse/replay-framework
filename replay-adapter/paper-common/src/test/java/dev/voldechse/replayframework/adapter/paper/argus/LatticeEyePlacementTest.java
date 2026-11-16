package dev.voldechse.replayframework.adapter.paper.argus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import java.util.List;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class LatticeEyePlacementTest {

    private static final Key WORLD = Key.key("minecraft:overworld");
    private static final int TRACKING_RANGE = 64;

    private final LatticeEyePlacement placement = new LatticeEyePlacement(64, 8);

    @Test
    void coversASmallArenaWithOneCenteredEye() {
        List<EyeSpec> eyes = placement.plan(region(0, 60, 0, 63, 99, 31), TRACKING_RANGE);

        assertEquals(1, eyes.size());
        EyeSpec eye = eyes.get(0);
        assertEquals("minecraft:overworld", eye.world());
        assertEquals(32.0, eye.x());
        assertEquals(79.5, eye.y());
        assertEquals(16.0, eye.z());
        // Corner distance hypot(64, 32) / 2 = 35.8 blocks: three chunks plus margin.
        assertEquals(4, eye.viewDistance());
    }

    @Test
    void keepsEveryBlockWithinTrackingRangeAndSentChunksOfAnEye() {
        CuboidRegion region = region(-250, 0, -100, 249, 255, 299);

        List<EyeSpec> eyes = placement.plan(region, TRACKING_RANGE);

        assertTrue(eyes.size() > 1);
        for (int x = region.min().x(); x <= region.max().x(); x += 7) {
            for (int z = region.min().z(); z <= region.max().z(); z += 7) {
                double nearest = Double.MAX_VALUE;
                int viewDistance = 0;
                for (EyeSpec eye : eyes) {
                    double distance = Math.hypot(eye.x() - (x + 0.5), eye.z() - (z + 0.5));
                    if (distance < nearest) {
                        nearest = distance;
                        viewDistance = eye.viewDistance();
                    }
                }
                assertTrue(nearest <= TRACKING_RANGE, "block " + x + "," + z + " is not tracked");
                assertTrue(nearest <= (viewDistance - 1) * 16.0,
                        "block " + x + "," + z + " is outside the sent chunks");
            }
        }
    }

    @Test
    void neverExceedsTheViewDistanceLimit() {
        LatticeEyePlacement narrow = new LatticeEyePlacement(64, 4);

        List<EyeSpec> eyes = narrow.plan(region(0, 0, 0, 399, 64, 399), 128);

        assertTrue(eyes.stream().allMatch(eye -> eye.viewDistance() <= 4));
    }

    @Test
    void rejectsARegionBeyondTheEyeBudget() {
        LatticeEyePlacement small = new LatticeEyePlacement(4, 8);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> small.plan(region(0, 0, 0, 999, 64, 999), TRACKING_RANGE));

        assertTrue(failure.getMessage().contains("budget"));
    }

    private static CuboidRegion region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new CuboidRegion(
                WORLD, new BlockPosition(minX, minY, minZ), new BlockPosition(maxX, maxY, maxZ));
    }
}
