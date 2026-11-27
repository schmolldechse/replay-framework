package dev.voldechse.replayframework.adapter.paper.playback;

import io.netty.channel.Channel;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The ordered queue of work for one viewer connection: replay frames to
 * send and view resets.
 *
 * <p>Operations run on the connection's event loop in the order they were
 * added, at most {@link #MAX_OPERATIONS_PER_TURN} per turn, so a large
 * reconstruction never blocks the connection; the rest continues after
 * {@link #BATCH_DELAY_MILLIS}. Each turn that ran an operation ends with one
 * flush. Waiters for an idle queue complete once everything added before
 * them ran and was flushed.</p>
 */
public final class ViewerOutboundQueue {

    /** The most operations one event-loop turn runs. */
    public static final int MAX_OPERATIONS_PER_TURN = 128;
    /** The pause between two turns of a long queue. */
    public static final long BATCH_DELAY_MILLIS = 1L;

    private final Channel channel;
    private final Runnable flush;
    private final BooleanSupplier terminal;
    private final Consumer<Throwable> failures;
    private final Object lock = new Object();
    private final ArrayDeque<Runnable> operations = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<Void>> idleWaiters = new ArrayDeque<>();
    private boolean drainScheduled;

    /**
     * @param channel the viewer's connection
     * @param flush flushes what the operations wrote
     * @param terminal whether the playback ended; the queue then stops
     * @param failures receives a failed operation; the queue is cleared
     */
    public ViewerOutboundQueue(
            Channel channel, Runnable flush, BooleanSupplier terminal, Consumer<Throwable> failures) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.flush = Objects.requireNonNull(flush, "flush");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /** Adds operations to the end of the queue. */
    public void enqueue(List<Runnable> added) {
        List<Runnable> accepted = List.copyOf(added);
        if (accepted.isEmpty()) {
            return;
        }
        boolean scheduleDrain;
        synchronized (lock) {
            if (terminal.getAsBoolean()) {
                return;
            }
            operations.addAll(accepted);
            scheduleDrain = !drainScheduled;
            if (scheduleDrain) {
                drainScheduled = true;
            }
        }
        if (!scheduleDrain) {
            return;
        }
        try {
            channel.eventLoop().execute(this::drain);
        } catch (Throwable failure) {
            clear();
            failures.accept(failure);
        }
    }

    /** Completes once every operation added before ran and was flushed. */
    public CompletionStage<Void> awaitIdle() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (lock) {
            if (terminal.getAsBoolean()) {
                result.completeExceptionally(new IllegalStateException("playback is no longer active"));
            } else if (operations.isEmpty() && !drainScheduled) {
                result.complete(null);
            } else {
                idleWaiters.addLast(result);
            }
        }
        return result;
    }

    /** Drops every queued operation; waiting callers fail with the reason. */
    public void clear(Throwable reason) {
        ArrayDeque<CompletableFuture<Void>> waiters;
        synchronized (lock) {
            operations.clear();
            drainScheduled = false;
            waiters = new ArrayDeque<>(idleWaiters);
            idleWaiters.clear();
        }
        for (CompletableFuture<Void> waiter : waiters) {
            waiter.completeExceptionally(reason);
        }
    }

    /** Drops every queued operation. */
    public void clear() {
        clear(new IllegalStateException("viewer outbound queue was cleared"));
    }

    private void drain() {
        int processed = 0;
        while (processed < MAX_OPERATIONS_PER_TURN) {
            Runnable operation;
            synchronized (lock) {
                if (terminal.getAsBoolean()) {
                    operations.clear();
                    drainScheduled = false;
                    completeWaiters(new IllegalStateException("playback is no longer active"));
                    return;
                }
                operation = operations.pollFirst();
            }
            if (operation == null) {
                boolean finished;
                synchronized (lock) {
                    finished = operations.isEmpty() || terminal.getAsBoolean();
                    if (finished) {
                        operations.clear();
                        drainScheduled = false;
                    }
                }
                if (finished) {
                    try {
                        flush(processed);
                        completeWaiters(null);
                    } catch (Throwable failure) {
                        clear();
                        failures.accept(failure);
                    }
                    return;
                }
                continue;
            }
            try {
                operation.run();
            } catch (Throwable failure) {
                clear();
                failures.accept(failure);
                return;
            }
            processed++;
        }

        try {
            flush(processed);
        } catch (Throwable failure) {
            clear();
            failures.accept(failure);
            return;
        }

        synchronized (lock) {
            if (operations.isEmpty() || terminal.getAsBoolean()) {
                operations.clear();
                drainScheduled = false;
                completeWaiters(terminal.getAsBoolean()
                        ? new IllegalStateException("playback is no longer active")
                        : null);
                return;
            }
        }
        try {
            channel.eventLoop().schedule(this::drain, BATCH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            clear();
            failures.accept(failure);
        }
    }

    private void flush(int processed) {
        if (processed > 0) {
            flush.run();
        }
    }

    private void completeWaiters(Throwable failure) {
        ArrayDeque<CompletableFuture<Void>> waiters;
        synchronized (lock) {
            if (failure == null && (!operations.isEmpty() || drainScheduled)) {
                return;
            }
            waiters = new ArrayDeque<>(idleWaiters);
            idleWaiters.clear();
        }
        for (CompletableFuture<Void> waiter : waiters) {
            if (failure == null) {
                waiter.complete(null);
            } else {
                waiter.completeExceptionally(failure);
            }
        }
    }
}
