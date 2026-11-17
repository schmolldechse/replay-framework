package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.format.TrackSubject;
import java.util.Objects;

/**
 * The subject whose state a packet describes: a player whose own connection
 * received it, or an entity whose inventory was read. A recording keeps such
 * packets in the subject's state track instead of the scene.
 *
 * @param subject the subject
 * @param label display name for the track, such as a player's name or an
 *              entity type; may be empty
 */
public record CaptureSubject(TrackSubject subject, String label) {

    public CaptureSubject {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(label, "label");
    }
}
