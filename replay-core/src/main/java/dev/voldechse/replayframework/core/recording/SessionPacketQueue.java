package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.core.capture.CapturedPacket;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded multi-producer/single-consumer queue for one recording session.
 *
 * <p>The byte counter is the admission control. A producer reserves its
 * packet's defensive payload size before publishing an entry, so an overflow
 * cannot look like a successfully accepted packet. The queue never waits for
 * space; the session converts {@link QueueOverflowException} into its
 * terminal {@code QUEUE_OVERFLOW} failure.</p>
 */
final class SessionPacketQueue {

    private enum State {
        /** New entries may reserve bytes and publish. */
        OPEN,
        /** New reservations are blocked; already reserved producers may publish. */
        CLOSING,
        /** Closing was observed after all entries and producers drained. */
        CLOSED
    }

    private record Entry(CapturedPacket packet, long bytes) {
    }

    private final long maxBytes;
    private final ConcurrentLinkedQueue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicInteger producersInFlight = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final Semaphore signal = new Semaphore(0);

    SessionPacketQueue(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    /**
     * Reserves bytes and publishes one packet without waiting for a consumer.
     * A producer that already passed the OPEN check is allowed to publish while
     * close is racing; otherwise close could strand a reserved packet.
     */
    void enqueue(CapturedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        producersInFlight.incrementAndGet();
        try {
            if (state.get() != State.OPEN) {
                throw new IllegalStateException("session packet queue is closed for enqueue");
            }

            long bytes = Math.max(1L, packet.payload().length);
            reserve(bytes);
            entries.add(new Entry(packet, bytes));
            signal.release();
        } finally {
            producersInFlight.decrementAndGet();
            // A waiter may have observed CLOSING while this producer was
            // publishing its already-reserved entry.
            signal.release();
        }
    }

    /** Returns the current reserved payload-byte count. */
    long queuedBytes() {
        return queuedBytes.get();
    }

    /**
     * Removes the next packet, waiting only on the writer-side signal.
     * {@link Optional#empty()} is returned only after closing, all in-flight
     * producers and all previously published entries have been observed.
     */
    Optional<CapturedPacket> awaitNext() throws InterruptedException {
        for (;;) {
            Entry entry = entries.poll();
            if (entry != null) {
                queuedBytes.addAndGet(-entry.bytes());
                return Optional.of(entry.packet());
            }

            if (state.get() != State.OPEN && producersInFlight.get() == 0) {
                state.set(State.CLOSED);
                return Optional.empty();
            }
            signal.acquire();
        }
    }

    /** Returns true only after closing has been observed and the queue drained. */
    boolean closed() {
        return state.get() == State.CLOSED;
    }

    /** Prevents new reservations while preserving all already published entries. */
    void closeForEnqueue() {
        state.compareAndSet(State.OPEN, State.CLOSING);
        signal.release();
    }

    /** Idempotently closes admission and wakes a waiting writer. */
    void close() {
        closeForEnqueue();
    }

    private void reserve(long bytes) {
        for (;;) {
            long current = queuedBytes.get();
            final long next;
            try {
                next = Math.addExact(current, bytes);
            } catch (ArithmeticException overflow) {
                throw new QueueOverflowException(maxBytes, bytes);
            }
            if (next > maxBytes) {
                throw new QueueOverflowException(maxBytes, bytes);
            }
            if (queuedBytes.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /** Signals that a packet could not be admitted without silently dropping it. */
    static final class QueueOverflowException extends RuntimeException {
        QueueOverflowException(long maxBytes, long requestedBytes) {
            super("capture queue byte budget exceeded: max=" + maxBytes
                    + ", requested=" + requestedBytes);
        }
    }
}
