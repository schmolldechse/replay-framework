package dev.voldechse.replayframework.adapter.paper.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ViewerOutboundQueueTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final List<String> ran = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private int flushes;
    private boolean terminal;

    private final ViewerOutboundQueue queue = new ViewerOutboundQueue(
            channel, () -> flushes++, () -> terminal, failures::add);

    @Test
    void runsOperationsInOrderAndFlushesOncePerTurn() {
        queue.enqueue(List.of(() -> ran.add("a"), () -> ran.add("b")));
        queue.enqueue(List.of(() -> ran.add("c")));
        CompletableFuture<Void> idle = queue.awaitIdle().toCompletableFuture();

        channel.runPendingTasks();

        assertEquals(List.of("a", "b", "c"), ran);
        assertEquals(1, flushes);
        assertTrue(idle.isDone() && !idle.isCompletedExceptionally());
    }

    @Test
    void aLongQueueYieldsTheEventLoopBetweenTurns() {
        List<Runnable> operations = new ArrayList<>();
        for (int index = 0; index < ViewerOutboundQueue.MAX_OPERATIONS_PER_TURN + 5; index++) {
            operations.add(() -> ran.add("x"));
        }
        queue.enqueue(operations);

        channel.runPendingTasks();
        assertEquals(ViewerOutboundQueue.MAX_OPERATIONS_PER_TURN, ran.size());

        channel.advanceTimeBy(ViewerOutboundQueue.BATCH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
        assertEquals(ViewerOutboundQueue.MAX_OPERATIONS_PER_TURN + 5, ran.size());
        assertEquals(2, flushes);
    }

    @Test
    void clearingDropsQueuedWorkAndFailsWaiters() {
        queue.enqueue(List.of(() -> ran.add("a")));
        CompletableFuture<Void> idle = queue.awaitIdle().toCompletableFuture();

        queue.clear(new IllegalStateException("superseded"));
        channel.runPendingTasks();

        assertTrue(ran.isEmpty());
        assertTrue(idle.isCompletedExceptionally());
    }

    @Test
    void aFailingOperationStopsTheQueueAndIsReported() {
        queue.enqueue(List.of(() -> { throw new IllegalStateException("broken"); }, () -> ran.add("b")));

        channel.runPendingTasks();

        assertTrue(ran.isEmpty());
        assertEquals(1, failures.size());
    }

    @Test
    void anEndedPlaybackAcceptsNoWork() {
        terminal = true;
        queue.enqueue(List.of(() -> ran.add("a")));

        channel.runPendingTasks();

        assertTrue(ran.isEmpty());
        assertTrue(queue.awaitIdle().toCompletableFuture().isCompletedExceptionally());
    }
}
