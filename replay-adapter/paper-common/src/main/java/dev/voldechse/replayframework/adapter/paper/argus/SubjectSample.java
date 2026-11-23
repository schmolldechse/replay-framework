package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One look at a subject's state on the server thread.
 *
 * <p>The signature is cheap to compute and changes whenever the state does.
 * Only when it changed, or when the subject is new to a recording, Argus
 * takes a {@link Reading} and publishes it.</p>
 *
 * @param subject the subject and its display label
 * @param cadence how often the subject is looked at
 * @param signature a value that changes whenever the recorded state changes
 * @param reading takes a reading of the complete state; only called when it
 *                is published
 */
public record SubjectSample(
        CaptureSubject subject,
        Cadence cadence,
        long signature,
        Supplier<Reading> reading) {

    public SubjectSample {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(cadence, "cadence");
        Objects.requireNonNull(reading, "reading");
    }

    /** How often a subject is looked at. */
    public enum Cadence {
        /**
         * Every server tick, for subjects whose state changes often and is
         * watched closely, such as a player's inventory.
         */
        EVERY_TICK,
        /** Every sampling interval of the recording, such as an entity's inventory. */
        INTERVAL
    }

    /** A snapshot of a subject's state, as native packets. */
    public interface Reading {

        /** The native packets that carry the complete state. */
        List<Object> packets();

        /**
         * The native packets that turn the previous reading into this one,
         * such as only the slots that changed. The default is the complete
         * state.
         *
         * @param previous the reading published before
         */
        default List<Object> changesSince(Reading previous) {
            return packets();
        }
    }
}
