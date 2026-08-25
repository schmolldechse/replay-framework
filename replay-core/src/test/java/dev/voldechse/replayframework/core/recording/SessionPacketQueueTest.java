package dev.voldechse.replayframework.core.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class SessionPacketQueueTest {

    @Test
    void enqueueReservesBytesBeforePublishingEntry() throws InterruptedException {
        SessionPacketQueue queue = new SessionPacketQueue(4L);
        CapturedPacket packet = packet(new byte[] {1, 2, 3});

        queue.enqueue(packet);

        assertEquals(3L, queue.queuedBytes());
        assertSame(packet, queue.awaitNext().orElseThrow());
        assertEquals(0L, queue.queuedBytes());
    }

    @Test
    void zeroLengthPayloadConsumesOneBudgetByte() {
        SessionPacketQueue queue = new SessionPacketQueue(1L);

        queue.enqueue(packet(new byte[0]));

        assertEquals(1L, queue.queuedBytes());
        assertThrows(SessionPacketQueue.QueueOverflowException.class,
                () -> queue.enqueue(packet(new byte[0])));
    }

    @Test
    void overflowDoesNotPublishPacketOrChangeReservation() throws InterruptedException {
        SessionPacketQueue queue = new SessionPacketQueue(2L);
        CapturedPacket packet = packet(new byte[] {1, 2, 3});

        assertThrows(SessionPacketQueue.QueueOverflowException.class, () -> queue.enqueue(packet));
        assertEquals(0L, queue.queuedBytes());

        queue.closeForEnqueue();
        assertTrue(queue.awaitNext().isEmpty());
    }

    @Test
    void closePreservesAlreadyQueuedEntriesAndRejectsNewEntries() throws InterruptedException {
        SessionPacketQueue queue = new SessionPacketQueue(8L);
        CapturedPacket first = packet(new byte[] {1});
        CapturedPacket second = packet(new byte[] {2, 3});
        queue.enqueue(first);
        queue.enqueue(second);

        queue.closeForEnqueue();

        assertThrows(IllegalStateException.class, () -> queue.enqueue(packet(new byte[] {4})));
        assertSame(first, queue.awaitNext().orElseThrow());
        assertSame(second, queue.awaitNext().orElseThrow());
        assertTrue(queue.awaitNext().isEmpty());
        assertTrue(queue.closed());
    }

    @Test
    void concurrentProducersNeverExceedByteLimit() throws Exception {
        long limit = 8L;
        SessionPacketQueue queue = new SessionPacketQueue(limit);
        ExecutorService producers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger overflowed = new AtomicInteger();
        AtomicLong maximumQueuedBytes = new AtomicLong();
        List<Runnable> jobs = new ArrayList<>();

        for (int producer = 0; producer < 4; producer++) {
            jobs.add(() -> {
                await(start);
                for (int index = 0; index < 20; index++) {
                    try {
                        queue.enqueue(packet(new byte[] {(byte) index}));
                        accepted.incrementAndGet();
                        maximumQueuedBytes.accumulateAndGet(queue.queuedBytes(), Math::max);
                    } catch (SessionPacketQueue.QueueOverflowException expected) {
                        overflowed.incrementAndGet();
                    }
                }
            });
        }

        jobs.forEach(producers::submit);
        start.countDown();
        producers.shutdown();
        assertTrue(producers.awaitTermination(10, TimeUnit.SECONDS));
        queue.closeForEnqueue();

        int drained = 0;
        while (queue.awaitNext().isPresent()) {
            drained++;
        }

        assertEquals(accepted.get(), drained);
        assertTrue(overflowed.get() > 0);
        assertTrue(maximumQueuedBytes.get() <= limit);
        assertEquals(0L, queue.queuedBytes());
    }

    private static CapturedPacket packet(byte[] payload) {
        return new CapturedPacket(
                java.util.UUID.randomUUID(),
                10L,
                1L,
                0,
                PacketPhase.PLAY,
                1,
                payload);
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
