package dev.voldechse.replayframework.adapter;

/**
 * Where a captured packet was observed.
 *
 * <p>A recording takes its scene only from its virtual observers, so the same
 * scene change is never recorded twice. Player connections contribute only
 * the packets no observer can receive.</p>
 */
public enum CaptureOrigin {
    /** Scene state seen by a virtual observer that belongs to a recording. */
    OBSERVER,
    /**
     * A packet from a player's connection that no virtual observer can
     * receive: one the server only sends to listed players, such as chat, the
     * tab list, a sound or a particle, or an effect it sends to everyone
     * except the acting player. Every recording accepts it.
     */
    PLAYER_ONLY
}
