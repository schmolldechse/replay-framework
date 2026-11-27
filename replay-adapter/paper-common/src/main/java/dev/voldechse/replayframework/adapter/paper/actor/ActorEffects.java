package dev.voldechse.replayframework.adapter.paper.actor;

import io.netty.channel.Channel;

/**
 * Derives the effects the server sends to everyone except a player who acted
 * on a block, implemented once per Minecraft version.
 */
@FunctionalInterface
public interface ActorEffects {

    /**
     * Handles one action on the acting player's network thread.
     * {@code handOver} passes the packet on to the server and must run
     * exactly once, whatever happens.
     *
     * @param channel the acting player's connection
     * @param packet the received packet object
     * @param action what the player did
     * @param handOver passes the packet on to the server
     */
    void around(Channel channel, Object packet, ActorAction action, Runnable handOver);
}
