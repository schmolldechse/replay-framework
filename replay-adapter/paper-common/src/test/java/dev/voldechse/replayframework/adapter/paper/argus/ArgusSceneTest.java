package dev.voldechse.replayframework.adapter.paper.argus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArgusSceneTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private final ArgusScene scene = new ArgusScene();

    @Test
    void publishesABroadcastOnceForAllEyes() {
        Object packet = new Object();

        assertTrue(scene.broadcast(A, packet, 10L));
        assertFalse(scene.broadcast(B, packet, 10L));
    }

    @Test
    void keepsARepetitionForTheSameEye() {
        Object packet = new Object();

        assertTrue(scene.broadcast(A, packet, 10L));
        assertTrue(scene.broadcast(A, packet, 10L));
    }

    @Test
    void doesNotCompareBroadcastsByContent() {
        assertTrue(scene.broadcast(A, "move", 10L));
        assertTrue(scene.broadcast(B, new String("move"), 10L));
    }

    @Test
    void forgetsBroadcastsOfEarlierTicks() {
        Object packet = new Object();

        assertTrue(scene.broadcast(A, packet, 10L));
        assertTrue(scene.broadcast(B, packet, 11L));
    }

    @Test
    void gainsAChunkWithItsFirstEyeAndLosesItWithItsLast() {
        assertTrue(scene.chunkGained(A, 3, -2));
        assertFalse(scene.chunkGained(B, 3, -2));

        assertFalse(scene.chunkLost(A, 3, -2));
        assertTrue(scene.chunkLost(B, 3, -2));
    }

    @Test
    void keepsChunksWithEqualLowBitsApart() {
        assertTrue(scene.chunkGained(A, 1, -1));
        assertTrue(scene.chunkGained(A, 0, -1));
        assertTrue(scene.chunkGained(A, -1, 1));
    }

    @Test
    void publishesAChunkResentToItsHolder() {
        assertTrue(scene.chunkGained(A, 0, 0));
        assertTrue(scene.chunkGained(A, 0, 0));
    }

    @Test
    void ignoresALossOfSomethingTheEyeNeverHeld() {
        assertTrue(scene.chunkGained(A, 0, 0));

        assertFalse(scene.chunkLost(B, 0, 0));
        assertTrue(scene.chunkLost(A, 0, 0));
    }

    @Test
    void reportsOnlyEntitiesNoEyeHoldsAnymore() {
        assertTrue(scene.entityGained(A, 7));
        assertTrue(scene.entityGained(A, 8));
        assertFalse(scene.entityGained(B, 8));

        assertArrayEquals(new int[] {7}, scene.entitiesLost(A, new int[] {7, 8}));
        assertArrayEquals(new int[] {8}, scene.entitiesLost(B, new int[] {8}));
    }

    @Test
    void releasesWhatOnlyTheClosedEyeWasHolding() {
        scene.chunkGained(A, 1, 1);
        scene.chunkGained(A, 2, 2);
        scene.chunkGained(B, 2, 2);
        scene.entityGained(A, 9);
        scene.entityGained(A, 4);
        scene.entityGained(B, 4);

        ArgusScene.Released released = scene.release(A);

        assertEquals(List.of(new ArgusScene.ChunkPosition(1, 1)), released.chunks());
        assertArrayEquals(new int[] {9}, released.entityIds());
        assertTrue(scene.chunkLost(B, 2, 2));
        assertArrayEquals(new int[] {4}, scene.entitiesLost(B, new int[] {4}));
    }
}
