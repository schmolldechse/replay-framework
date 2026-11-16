package dev.voldechse.replayframework.adapter;

/**
 * Where a captured packet was observed.
 *
 * <p>A recording takes its scene from exactly one source so that the same
 * scene change is never recorded twice. The origin lets the core pick that
 * source per recording without knowing how an adapter observes packets.</p>
 */
public enum CaptureOrigin {
    /**
     * Scene state as one player's view received it, or sampled from that
     * player: chunks, blocks and tracked entities.
     */
    PLAYER_VIEW,
    /** Scene state seen by a virtual observer that belongs to a recording. */
    OBSERVER,
    /**
     * A packet the server only sends to listed players, such as chat, the
     * tab list, a sound or a particle. No virtual observer can receive it,
     * so every recording accepts it regardless of its scene source.
     */
    PLAYER_ONLY
}
