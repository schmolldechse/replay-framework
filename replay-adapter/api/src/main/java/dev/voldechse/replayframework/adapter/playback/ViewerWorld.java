package dev.voldechse.replayframework.adapter.playback;

import java.util.Optional;

/**
 * The recorded world a viewer watches. A recording can span several worlds,
 * and a client can only apply the frames of one: a chunk of another
 * dimension has a different height and breaks the client's connection.
 */
public interface ViewerWorld {

    /**
     * Chooses the recorded world the viewer watches. Frames of other worlds
     * are not sent; frames of no world, such as chat, always are. The
     * viewer's own live world does not matter, because playback places the
     * viewer in a world of its own.
     */
    void viewWorld(String recordedWorld);

    /** The recorded world the viewer watches, when one was chosen. */
    Optional<String> viewedWorld();
}
