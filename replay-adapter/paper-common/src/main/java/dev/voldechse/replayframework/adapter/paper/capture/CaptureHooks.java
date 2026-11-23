package dev.voldechse.replayframework.adapter.paper.capture;

import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.argus.ArgusPlatform;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinitionCatalog;
import io.netty.channel.Channel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * The version-specific half of capture, implemented once per Minecraft
 * version. Everything else, from the connection handlers to Argus and the
 * capture order, is shared in {@link PaperCaptureBridge}.
 */
public interface CaptureHooks {

    /** The live packet registry the adapter verified. */
    PacketRegistry registry();

    /** One definition per clientbound PLAY packet of this version. */
    ClientboundPacketDefinitionCatalog<?> definitions();

    /** The eyes, sampled state and platform queries of this version. */
    ArgusPlatform argus();

    /** The current server tick. */
    long serverTick();

    /** The open connection of an online player. */
    Channel channel(Player player);

    /** The player a connection belongs to, once it is in the game. */
    Optional<ConnectionPlayer> player(Channel channel);

    /** The packets of a bundle, or the message itself. */
    List<Object> unbundle(Object message);

    /**
     * Encodes a packet a connection is sent, with that connection's live
     * codec. Empty when the message is not a PLAY packet of this version.
     */
    Optional<PacketCodec.EncodedPacket> encodeSent(Channel channel, Object message);

    /**
     * Encodes a PLAY packet built on the server thread, such as one of an
     * eye. Empty when the packet's definition is not observable by eyes.
     */
    Optional<PacketCodec.EncodedPacket> encodeObserved(Object packet);

    /** Encodes a PLAY packet that carries state or an actor effect. */
    PacketCodec.EncodedPacket encodeState(Object packet);

    /** Calls the action with every connection opened from now on, until {@link #stopWatchingConnections()}. */
    void watchConnections(Consumer<Channel> action);

    /** Stops calling the action of {@link #watchConnections}. */
    void stopWatchingConnections();

    /**
     * Adds the version's own handlers to a connection, such as the receiver
     * of serverbound packets. Called on the connection's event loop.
     */
    void installHandlers(Channel channel, CaptureCallbacks callbacks);

    /** Removes the handlers {@link #installHandlers} added. */
    void uninstallHandlers(Channel channel);

    /**
     * The player of a connection.
     *
     * @param id the profile UUID
     * @param name the profile name
     */
    record ConnectionPlayer(UUID id, String name) {
    }

    /** What the version's handlers may ask of capture. */
    interface CaptureCallbacks {

        /** Whether capture is installed and trusted. */
        boolean installed();

        /** Whether a recording is active, so derived packets are worth building. */
        boolean recording();

        /**
         * Publishes a packet the server sent everyone except the acting
         * player, rebuilt for the recording; see the capture source policy.
         *
         * @param packet the native packet
         * @param actor the acting player's connection
         */
        void publishActorEffect(Object packet, UUID actor);

        /** Reports a failure that breaks capture integrity. */
        void fail(Throwable failure);
    }
}
