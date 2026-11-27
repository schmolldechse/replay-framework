package dev.voldechse.replayframework.adapter.paper.packet.clientbound;

/**
 * How the server distributes a clientbound packet among its recipients.
 *
 * <p>The distribution decides whether a virtual observer can deliver the
 * packet at all, and how observations of several recipients merge into one
 * scene without comparing payloads.</p>
 */
public enum PacketDistribution {
    /**
     * The server sends the packet to listed players only, directly or
     * through the player list: chat, the tab list, sounds, particles,
     * inventories. Virtual observers never receive it. This is the default.
     */
    PLAYER_ONLY,
    /**
     * The chunk system or the entity tracker sends one packet object to
     * every recipient that sees the change, for example entity movement or
     * a block update.
     */
    BROADCAST,
    /**
     * The packet adds something to or removes something from the view of one
     * recipient: a chunk or an entity. The definition names it through
     * {@link ClientboundPacketDefinition#sceneMembership(Object)}.
     */
    VIEW_MEMBERSHIP,
    /** The packet describes the view of one recipient and is never part of the scene. */
    VIEW_LOCAL;

    /** Whether a virtual observer receives packets of this kind. */
    public boolean observable() {
        return this != PLAYER_ONLY;
    }
}
