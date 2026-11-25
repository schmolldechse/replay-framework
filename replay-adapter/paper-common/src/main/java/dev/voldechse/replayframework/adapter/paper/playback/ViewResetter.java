package dev.voldechse.replayframework.adapter.paper.playback;

/**
 * Removes what a replay added to a viewer's client. It watches the packets
 * sent to the viewer and remembers what carries a replay-owned identity:
 * entities, block cracks, boss bars, tab-list entries. Implemented once per
 * version, because the removal packets differ.
 */
public interface ViewResetter {

    /** Notes one packet that was sent to the viewer. */
    void observe(Object packet);

    /** Removes what the replay shows, before the view is rebuilt by a seek. */
    void reset();

    /** Removes what would outlive the playback, when it closes. */
    void detach();
}
