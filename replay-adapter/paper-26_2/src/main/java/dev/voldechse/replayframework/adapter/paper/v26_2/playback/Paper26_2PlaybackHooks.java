package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.AdapterDescriptor;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.channel.PipelineNames;
import dev.voldechse.replayframework.adapter.paper.playback.PlaybackHooks;
import dev.voldechse.replayframework.adapter.paper.playback.ViewResetter;
import dev.voldechse.replayframework.adapter.paper.playback.ViewerParts;
import dev.voldechse.replayframework.adapter.paper.playback.ViewerRewriter;
import dev.voldechse.replayframework.adapter.paper.playback.ViewerScene;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.bukkit.entity.Player;

/**
 * The Paper 26.2 half of playback: the viewer connection's live codec, the
 * classification of live packets, and the rewriter, resetter, scene and
 * interaction handler of each viewer.
 */
public final class Paper26_2PlaybackHooks implements PlaybackHooks {

    private static final String ADAPTER_ID = "paper-26.2";
    private static final String CLIENTBOUND_PREFIX = "play.clientbound.";

    private final PaperConnectionAccessor accessor;
    private final PacketRegistry registry;
    private final Map<String, PacketDescriptor> clientbound = new HashMap<>();

    /**
     * @param accessor access to Paper connections
     * @param descriptor the adapter's descriptor; playback is bound to it
     * @param registry the verified 26.2 registry
     */
    public Paper26_2PlaybackHooks(PaperConnectionAccessor accessor, AdapterDescriptor descriptor, PacketRegistry registry) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(descriptor, "descriptor");
        if (!ADAPTER_ID.equals(descriptor.adapterId())) {
            throw new IncompatibleAdapterException("unsupported playback adapter " + descriptor.adapterId());
        }
        if (!descriptor.registryFingerprint().equals(registry.fingerprint())) {
            throw new IncompatibleAdapterException("playback registry fingerprint does not match adapter descriptor");
        }
        for (PacketDescriptor packet : registry.descriptors()) {
            if (packet.phase() == PacketPhase.PLAY && packet.direction() == PacketDescriptor.Direction.CLIENTBOUND) {
                clientbound.put(packet.typeName(), packet);
            }
        }
    }

    @Override
    public PacketRegistry registry() {
        return registry;
    }

    @Override
    public Channel channel(Player viewer) {
        PaperConnectionAccessor.ConnectionHandle connection = accessor.connectionFor(viewer);
        if (connection == null) {
            throw new IllegalStateException("playback viewer has no open connection");
        }
        return connection.channel();
    }

    @Override
    public ViewerParts open(Player viewer, Channel channel, ViewerParts.Callbacks callbacks) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(callbacks, "callbacks");
        PaperConnectionAccessor.ConnectionHandle connection =
                new PaperConnectionAccessor.ConnectionHandle(viewer.getUniqueId(), channel);
        PacketCodec<Object> codec = new Paper26_2PacketCodec(accessor, connection, registry);
        return new Parts(
                codec,
                new Paper26_2ViewerScene(registry, codec),
                new Paper26_2ViewResetter(registry, callbacks::write, callbacks::fail));
    }

    /** The descriptor of a live clientbound packet object, by its type; no encoding needed. */
    Optional<PacketDescriptor> classify(Object livePacket) {
        if (!(livePacket instanceof Packet<?> packet)
                || packet.type() == null
                || packet.type().flow() != PacketFlow.CLIENTBOUND) {
            return Optional.empty();
        }
        return Optional.ofNullable(clientbound.get(CLIENTBOUND_PREFIX + packet.type().id()));
    }

    /** The parts of one viewer. */
    private final class Parts implements ViewerParts {
        private final PacketCodec<Object> codec;
        private final ViewerScene scene;
        private final ViewResetter resetter;

        private Parts(PacketCodec<Object> codec, ViewerScene scene, ViewResetter resetter) {
            this.codec = codec;
            this.scene = scene;
            this.resetter = resetter;
        }

        @Override
        public PacketCodec<Object> codec() {
            return codec;
        }

        @Override
        public Optional<PacketDescriptor> classify(Object livePacket) {
            return Paper26_2PlaybackHooks.this.classify(livePacket);
        }

        @Override
        public ViewerRewriter rewriter(PlaybackIdentityContext context, int observerEntityId) {
            return new Paper26_2ReplayPacketRewriter(context, Set.of(context.observerId()), Set.of(observerEntityId));
        }

        @Override
        public ViewResetter resetter() {
            return resetter;
        }

        @Override
        public ViewerScene scene() {
            return scene;
        }

        @Override
        public void installInteractions(Channel channel, Interactions interactions) {
            var pipeline = channel.pipeline();
            if (pipeline.get(ReplayEntityInteractions.HANDLER_NAME) != null
                    || pipeline.get(PipelineNames.PACKET_HANDLER) == null) {
                return;
            }
            pipeline.addBefore(
                    PipelineNames.PACKET_HANDLER,
                    ReplayEntityInteractions.HANDLER_NAME,
                    new ReplayEntityInteractions(
                            interactions::entity,
                            interactions::entityInteracted,
                            (pos, hand) -> interactions.block(new BlockPosition(pos.getX(), pos.getY(), pos.getZ()), hand),
                            interactions::blockInteracted,
                            interactions::fail));
        }

        @Override
        public void uninstallInteractions(Channel channel) {
            if (channel.pipeline().get(ReplayEntityInteractions.HANDLER_NAME) != null) {
                channel.pipeline().remove(ReplayEntityInteractions.HANDLER_NAME);
            }
        }
    }
}
