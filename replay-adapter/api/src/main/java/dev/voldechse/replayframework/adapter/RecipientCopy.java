package dev.voldechse.replayframework.adapter;

import java.util.Objects;
import java.util.UUID;

/**
 * One copy of an event the server sent to several players separately.
 *
 * <p>The server sends sounds, particles, explosions, chat and similar events
 * to every player in range, so a recording observes each of them once per
 * recipient. Copies of one event carry an equal {@code identity}; a recording
 * keeps only as many of them as a single recipient received.</p>
 *
 * @param recipient the connection that received this copy
 * @param identity what all copies of the event share; must implement value
 *                 equality, for example a record or a list of such values
 */
public record RecipientCopy(UUID recipient, Object identity) {

    public RecipientCopy {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(identity, "identity");
    }
}
