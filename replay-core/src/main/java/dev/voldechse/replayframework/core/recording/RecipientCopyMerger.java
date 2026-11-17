package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.RecipientCopy;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;

/**
 * Merges the copies of events the server sent to several players separately.
 *
 * <p>Copies of one event share an identity. Of all copies, a recording keeps
 * as many as a single recipient received: when one player received an event
 * twice, it happened twice; when two players received it once each, it
 * happened once.</p>
 *
 * <p>The copies of one event are sent in one server tick, but each recipient
 * connection writes its copy on its own thread. Under load a copy can
 * therefore be captured one tick later. An event stays open for the tick it
 * was first seen in and the next one. Two equal events in adjacent ticks
 * that reached disjoint recipients are therefore merged; equal events
 * include a random seed or a position for almost every kind of event, so
 * this is a known and accepted limit.</p>
 *
 * <p>Each recording owns one merger and applies it after deciding whether a
 * packet belongs to it, so a copy outside the recording's scope never hides
 * a copy inside it.</p>
 */
final class RecipientCopyMerger {

    /** Ticks an event stays open after the tick it was first seen in. */
    private static final long OPEN_TICKS = 1L;

    private final Map<EventKey, OpenEvent> open = new HashMap<>();
    private long sweptTick = -1L;

    /**
     * Returns whether a packet is to be recorded: it is no recipient copy, or
     * its recipient received this event more often than every other
     * recipient so far.
     */
    synchronized boolean record(CapturedPacket packet) {
        Objects.requireNonNull(packet, "packet");
        Optional<RecipientCopy> copy = packet.context().recipientCopy();
        if (copy.isEmpty()) {
            return true;
        }
        long tick = packet.serverTick();
        sweep(tick);
        // A world-wide packet, such as a broadcast message, reaches recipients
        // in every world; its copies differ only in the recipient's world.
        Key world = packet.context().worldWide() ? null : packet.context().world().orElse(null);
        EventKey key = new EventKey(packet.packetId(), world, copy.get().identity());
        OpenEvent event = open.get(key);
        if (event == null || event.expiredAt(tick)) {
            event = new OpenEvent(tick);
            open.put(key, event);
        }
        return event.observe(copy.get().recipient());
    }

    /** Number of open events; for tests. */
    synchronized int openEvents() {
        return open.size();
    }

    private void sweep(long tick) {
        if (tick <= sweptTick) {
            return;
        }
        sweptTick = tick;
        for (Iterator<OpenEvent> events = open.values().iterator(); events.hasNext(); ) {
            if (events.next().expiredAt(tick)) {
                events.remove();
            }
        }
    }

    private record EventKey(int packetId, Key world, Object identity) {}

    private static final class OpenEvent {
        private final long firstTick;
        private final Map<UUID, Integer> copiesByRecipient = new HashMap<>();
        private int recorded;

        private OpenEvent(long firstTick) {
            this.firstTick = firstTick;
        }

        private boolean expiredAt(long tick) {
            return tick > firstTick + OPEN_TICKS;
        }

        private boolean observe(UUID recipient) {
            int copies = copiesByRecipient.merge(recipient, 1, Integer::sum);
            if (copies <= recorded) {
                return false;
            }
            recorded = copies;
            return true;
        }
    }
}
