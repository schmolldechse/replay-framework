package dev.voldechse.replayframework.adapter;

import java.util.Objects;

/**
 * The track of a recording a captured packet belongs to. Exactly one route
 * applies to a packet; the recording derives the track and the world in
 * which a playback client applies the frame from it.
 */
public sealed interface TrackRoute {

    /** The scene of the packet's world. */
    TrackRoute SCENE = new Scene();

    /**
     * The packet's world track, but it concerns the whole world instead of a
     * place in it, such as a tab-list entry or public chat. A playback client
     * applies it in every world.
     */
    TrackRoute WORLD_WIDE = new WorldWide();

    /**
     * The block state track of the packet's world: the state of a block the
     * scene does not show, such as the contents of a chest.
     */
    TrackRoute BLOCK_STATE = new BlockState();

    /** The state track of a subject, such as a player's inventory. */
    static TrackRoute subject(CaptureSubject subject) {
        return new Subject(subject);
    }

    /** See {@link #SCENE}. */
    record Scene() implements TrackRoute {
    }

    /** See {@link #WORLD_WIDE}. */
    record WorldWide() implements TrackRoute {
    }

    /** See {@link #BLOCK_STATE}. */
    record BlockState() implements TrackRoute {
    }

    /**
     * The state track of a subject. Its packets belong to no world.
     *
     * @param subject the subject whose state the packet describes
     */
    record Subject(CaptureSubject subject) implements TrackRoute {
        public Subject {
            Objects.requireNonNull(subject, "subject");
        }
    }
}
