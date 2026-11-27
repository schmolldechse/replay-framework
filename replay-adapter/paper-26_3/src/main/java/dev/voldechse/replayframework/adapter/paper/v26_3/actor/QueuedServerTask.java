package dev.voldechse.replayframework.adapter.paper.v26_3.actor;

import java.util.Objects;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.MinecraftServer;

/**
 * A task placed into the server's queue of received packets.
 *
 * <p>The server handles received packets on its main thread in the order
 * the network thread queued them. A task queued directly before a packet
 * runs before the server handles it, and one queued directly after runs
 * after it. This is the only way to read the world exactly before and after
 * one packet without changing server code.</p>
 *
 * <p>The task never throws: the server would treat an exception as an error
 * of the player's connection.</p>
 */
record QueuedServerTask(
        PacketType<? extends Packet<ServerGamePacketListener>> type,
        Runnable task) implements Packet<ServerGamePacketListener> {

    QueuedServerTask {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(task, "task");
    }

    /**
     * Queues a task for a player's connection.
     *
     * @param listener the connection's game listener
     * @param origin the packet the task belongs to; it names the task in
     *               server diagnostics
     */
    @SuppressWarnings("unchecked")
    static void queue(ServerGamePacketListener listener, Packet<?> origin, Runnable task) {
        MinecraftServer.getServer().packetProcessor().scheduleIfPossible(
                listener,
                new QueuedServerTask(
                        (PacketType<? extends Packet<ServerGamePacketListener>>) origin.type(),
                        task));
    }

    @Override
    public void handle(ServerGamePacketListener listener) {
        task.run();
    }
}
