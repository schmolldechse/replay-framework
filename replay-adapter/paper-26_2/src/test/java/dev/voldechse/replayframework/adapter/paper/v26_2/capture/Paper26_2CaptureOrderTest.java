package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class Paper26_2CaptureOrderTest {

    @Test
    void allocatesTimeTickAndSequenceAsOnePublicationOrder() {
        AtomicLong nanoTime = new AtomicLong(100L);
        AtomicLong serverTick = new AtomicLong(20L);
        Paper26_2CaptureOrder order = new Paper26_2CaptureOrder(
                nanoTime::getAndIncrement, serverTick::get);
        List<CheckpointEncoder.CapturePoint> points = new ArrayList<>();

        order.publish(points::add);
        order.publish(points::add);
        serverTick.incrementAndGet();
        CheckpointEncoder.CapturePoint barrier = order.barrier();

        assertEquals(List.of(
                new CheckpointEncoder.CapturePoint(100L, 20L, 0),
                new CheckpointEncoder.CapturePoint(101L, 20L, 1)), points);
        assertEquals(new CheckpointEncoder.CapturePoint(102L, 21L, 0), barrier);
    }

    @Test
    void keepsPublicationInsideTheOrderingGate() throws Exception {
        AtomicLong nanoTime = new AtomicLong(100L);
        Paper26_2CaptureOrder order = new Paper26_2CaptureOrder(
                nanoTime::getAndIncrement, () -> 20L);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        List<CheckpointEncoder.CapturePoint> points =
                java.util.Collections.synchronizedList(new ArrayList<>());

        Thread first = Thread.startVirtualThread(() -> order.publish(point -> {
            firstEntered.countDown();
            await(releaseFirst);
            points.add(point);
        }));
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        Thread second = Thread.startVirtualThread(() -> {
            secondStarted.countDown();
            order.publish(point -> {
                secondEntered.countDown();
                points.add(point);
            });
        });
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
        assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));

        releaseFirst.countDown();
        first.join();
        second.join();

        assertEquals(List.of(
                new CheckpointEncoder.CapturePoint(100L, 20L, 0),
                new CheckpointEncoder.CapturePoint(101L, 20L, 1)), points);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
