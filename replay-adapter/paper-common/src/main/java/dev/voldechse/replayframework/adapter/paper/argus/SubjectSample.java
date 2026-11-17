package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One reading of a subject's state on the server thread.
 *
 * @param subject the subject and its display label
 * @param source how changes of this subject reach the recording
 * @param signature a value that changes whenever the state changes; compared
 *                  only for {@link Source#SAMPLED} subjects
 * @param packets builds the native packets that carry the complete state; only
 *                called when the state is published
 */
public record SubjectSample(
        CaptureSubject subject,
        Source source,
        long signature,
        Supplier<List<Object>> packets) {

    public SubjectSample {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(packets, "packets");
    }

    /** How the changes of a subject's state are recorded. */
    public enum Source {
        /**
         * The subject receives its changes itself, like a player's own
         * connection. Argus publishes its complete state only when it comes
         * into a recording.
         */
        OBSERVED,
        /**
         * Nobody receives the changes. Argus publishes the complete state when
         * the subject comes into a recording and whenever its signature changes.
         */
        SAMPLED
    }
}
