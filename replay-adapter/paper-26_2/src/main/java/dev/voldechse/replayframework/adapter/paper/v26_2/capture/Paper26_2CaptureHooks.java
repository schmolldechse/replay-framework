package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.argus.ArgusPlatform;
import dev.voldechse.replayframework.adapter.paper.capture.CaptureHooks;
import dev.voldechse.replayframework.adapter.paper.channel.PipelineNames;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.actor.Paper26_2ActorEffects;
import dev.voldechse.replayframework.adapter.paper.v26_2.argus.Paper26_2ArgusPlatform;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2NativePacketCodec;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.channel.Channel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.entity.Player;

/**
 * The Paper 26.2 half of capture: the live codec and definitions, the Argus
 * platform, the identity of a connection, Paper's hook for new connections,
 * and the serverbound receiver with its actor effects.
 */
public final class Paper26_2CaptureHooks implements CaptureHooks {

    private static final Key CONNECTION_LISTENER_KEY = Key.key("replayframework", "capture_handlers");

    private final PaperConnectionAccessor accessor;
    private final PacketRegistry registry;
    private final Paper26_2NativePacketCodec codec;
    private final Paper26_2ArgusPlatform argus = new Paper26_2ArgusPlatform();
    private volatile Paper26_2ActorEffects actorEffects;

    /**
     * @param accessor access to Paper connections
     * @param registry the verified 26.2 registry
     * @param codec encodes packets built on the server thread
     */
    public Paper26_2CaptureHooks(
            PaperConnectionAccessor accessor, PacketRegistry registry, Paper26_2NativePacketCodec codec) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public PacketRegistry registry() {
        return registry;
    }

    @Override
    public ClientboundPacketDefinitionCatalog<?> definitions() {
        return Paper26_2ClientboundPacketCatalog.catalog();
    }

    @Override
    public ArgusPlatform argus() {
        return argus;
    }

    @Override
    public long serverTick() {
        return accessor.currentServerTick();
    }

    @Override
    public Channel channel(Player player) {
        return accessor.connectionFor(player).channel();
    }

    @Override
    public Optional<ConnectionPlayer> player(Channel channel) {
        if (channel.pipeline().get(PipelineNames.PACKET_HANDLER) instanceof Connection connection
                && connection.getPacketListener() instanceof ServerGamePacketListenerImpl game) {
            return Optional.of(new ConnectionPlayer(game.player.getUUID(), game.player.getGameProfile().name()));
        }
        return Optional.empty();
    }

    @Override
    public List<Object> unbundle(Object message) {
        return Paper26_2ArgusPlatform.unbundlePacket(message);
    }

    @Override
    public Optional<PacketCodec.EncodedPacket> encodeSent(Channel channel, Object message) {
        if (!(message instanceof Packet<?>)) {
            return Optional.empty();
        }
        Optional<ConnectionPlayer> player = player(channel);
        if (player.isEmpty()) {
            return Optional.empty();
        }
        PaperConnectionAccessor.WirePacket wire = accessor.inspectAndEncode(
                new PaperConnectionAccessor.ConnectionHandle(player.get().id(), channel), message, registry);
        return wire.phase() == PacketPhase.PLAY
                ? Optional.of(clientbound(wire.packetId(), wire.payload()))
                : Optional.empty();
    }

    @Override
    public Optional<PacketCodec.EncodedPacket> encodeObserved(Object packet) {
        return codec.encodeObserved(nativePacket(packet))
                .map(encoded -> clientbound(encoded.packetId(), encoded.payload()));
    }

    @Override
    public PacketCodec.EncodedPacket encodeState(Object packet) {
        Paper26_2NativePacketCodec.EncodedStatePacket encoded = codec.encodeState(nativePacket(packet));
        return clientbound(encoded.packetId(), encoded.payload());
    }

    @Override
    public void watchConnections(Consumer<Channel> action) {
        Objects.requireNonNull(action, "action");
        io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(CONNECTION_LISTENER_KEY, action::accept);
    }

    @Override
    public void stopWatchingConnections() {
        io.papermc.paper.network.ChannelInitializeListenerHolder.removeListener(CONNECTION_LISTENER_KEY);
    }

    @Override
    public void installHandlers(Channel channel, CaptureCallbacks callbacks) {
        var pipeline = channel.pipeline();
        if (pipeline.get(Paper26_2ServerboundReceiver.HANDLER_NAME) != null
                || !(pipeline.get(PipelineNames.PACKET_HANDLER) instanceof Connection connection)) {
            return;
        }
        pipeline.addBefore(
                PipelineNames.PACKET_HANDLER,
                Paper26_2ServerboundReceiver.HANDLER_NAME,
                new Paper26_2ServerboundReceiver(
                        connection, callbacks::installed, actorEffects(callbacks), callbacks::fail));
    }

    @Override
    public void uninstallHandlers(Channel channel) {
        if (channel.pipeline().get(Paper26_2ServerboundReceiver.HANDLER_NAME) != null) {
            channel.pipeline().remove(Paper26_2ServerboundReceiver.HANDLER_NAME);
        }
    }

    /** The actor effects of the capture, created with its callbacks on first use. */
    private Paper26_2ActorEffects actorEffects(CaptureCallbacks callbacks) {
        Paper26_2ActorEffects current = actorEffects;
        if (current == null) {
            synchronized (this) {
                current = actorEffects;
                if (current == null) {
                    current = new Paper26_2ActorEffects(
                            callbacks::recording, callbacks::publishActorEffect, callbacks::fail);
                    actorEffects = current;
                }
            }
        }
        return current;
    }

    private static Packet<?> nativePacket(Object packet) {
        if (!(packet instanceof Packet<?> nativePacket)) {
            throw new IncompatibleAdapterException("capture packet is not a Paper 26.2 packet");
        }
        return nativePacket;
    }

    private static PacketCodec.EncodedPacket clientbound(int packetId, byte[] payload) {
        return new PacketCodec.EncodedPacket(
                PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, packetId, payload);
    }
}
