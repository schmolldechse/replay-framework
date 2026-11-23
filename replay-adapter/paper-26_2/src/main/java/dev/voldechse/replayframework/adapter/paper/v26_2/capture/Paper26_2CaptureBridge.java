package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.CaptureOrigin;
import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.adapter.paper.argus.Argus;
import dev.voldechse.replayframework.adapter.paper.channel.ClientboundTap;
import dev.voldechse.replayframework.adapter.paper.channel.PipelineNames;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.adapter.RecipientCopy;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.actor.Paper26_2ActorEffects;
import dev.voldechse.replayframework.adapter.paper.v26_2.argus.Paper26_2ArgusPlatform;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2NativePacketCodec;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.TrackSubject;
import io.netty.channel.Channel;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Global Paper 26.2 capture installation shared by all active recording
 * sessions. Session filtering remains in the core router.
 *
 * <p>Every player connection gets two handlers: a {@link ClientboundTap} that
 * sees each packet object the player is sent, before it is encoded, and the
 * serverbound receiver that hands received packets to their definitions.
 * Argus delivers the scene of its eyes on the server thread.</p>
 */
public final class Paper26_2CaptureBridge implements CaptureBridge {

    private static final net.kyori.adventure.key.Key SERVERBOUND_RECEIVER_KEY =
            net.kyori.adventure.key.Key.key("replayframework", "serverbound_receiver");

    private final PaperConnectionAccessor accessor;
    private final PacketRegistry registry;
    private final JavaPlugin owner;
    private final Paper26_2AuthoritativeStateIndex captureState;
    private final Paper26_2NativePacketCodec stateCodec;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final Object handlerStateLock = new Object();
    private final Paper26_2CaptureOrder captureOrder;
    private final Argus argus;
    private final Paper26_2ActorEffects actorEffects;
    private volatile BukkitTask argusTask;
    private volatile CaptureBridge.PacketSink sink;
    private volatile CaptureBridge.FailureHandler failureHandler;

    /** Creates a bridge and discovers the live Paper 26.2 registry. */
    public Paper26_2CaptureBridge(PaperConnectionAccessor accessor) {
        this(accessor, Paper26_2PacketRegistry.discover());
    }

    /**
     * Creates a bridge using an already verified Paper registry.
     *
     * @param accessor Paper connection boundary
     * @param registry registry published by the Paper 26.2 adapter
     */
    public Paper26_2CaptureBridge(PaperConnectionAccessor accessor, PacketRegistry registry) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
        this.owner = null;
        this.captureState = new Paper26_2AuthoritativeStateIndex();
        this.stateCodec = null;
        this.captureOrder = new Paper26_2CaptureOrder(
                System::nanoTime, this.accessor::currentServerTick);
        this.argus = null;
        this.actorEffects = null;
    }

    /** Creates the production bridge: Argus scenes, player-only packets and actor effects. */
    public Paper26_2CaptureBridge(
            JavaPlugin owner,
            PaperConnectionAccessor accessor,
            PacketRegistry registry,
            Paper26_2NativePacketCodec codec) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
        this.registry = verifyRegistry(registry);
        this.owner = Objects.requireNonNull(owner, "owner");
        this.stateCodec = Objects.requireNonNull(codec, "codec");
        this.captureState = new Paper26_2AuthoritativeStateIndex();
        this.captureOrder = new Paper26_2CaptureOrder(
                System::nanoTime, this.accessor::currentServerTick);
        this.actorEffects = new Paper26_2ActorEffects(
                this::hasActiveRecording, this::publishActorEffect, this::fail);
        this.argus = new Argus(
                new Paper26_2ArgusPlatform(),
                new dev.voldechse.replayframework.adapter.paper.argus.ArgusPublisher() {
                    @Override
                    public void publish(
                            UUID observationId, UUID sceneId, String world, Object packet) {
                        publishObserved(observationId, sceneId, world, packet);
                    }

                    @Override
                    public void publishState(UUID observationId, CaptureSubject subject, Object packet) {
                        publishSubjectState(observationId, subject, packet);
                    }

                    @Override
                    public void publishBlockState(UUID observationId, String world, Object packet) {
                        Paper26_2CaptureBridge.this.publishBlockState(observationId, world, packet);
                    }

                    @Override
                    public void sceneClosed(UUID sceneId) {
                        captureState.removeConnection(sceneId);
                    }
                },
                this.accessor::currentServerTick,
                this::fail);
    }

    /** Returns Argus, the virtual-observer source of this bridge. */
    public SceneObservation sceneObservation() {
        return argus == null ? SceneObservation.none() : argus;
    }

    @Override
    public void install(PacketSink sink, FailureHandler failureHandler) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(failureHandler, "failureHandler");

        synchronized (handlerStateLock) {
            State current = state.get();
            if (current == State.INSTALLED) {
                if (this.sink == sink && this.failureHandler == failureHandler) {
                    return;
                }
                throw new IllegalStateException("Paper capture bridge is already installed");
            }
            if (current != State.NEW) {
                throw new IllegalStateException("Paper capture bridge cannot be installed in state " + current);
            }
            this.sink = sink;
            this.failureHandler = failureHandler;
            state.set(State.INSTALLED);
        }

        try {
            seedOnlineConnections();
            installChannelHandlers();
            if (argus != null) {
                argusTask = Bukkit.getScheduler().runTaskTimer(owner, argus::tick, 1L, 1L);
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @Override
    public void uninstall() {
        State previous;
        synchronized (handlerStateLock) {
            previous = state.getAndSet(State.UNINSTALLED);
        }
        if (previous == State.UNINSTALLED) {
            return;
        }

        // UNINSTALLED is an expected lifecycle end, not an integrity failure.
        uninstallChannelHandlers();
        closeArgus();
        captureState.clear();
    }

    @Override
    public boolean installed() {
        return state.get() == State.INSTALLED;
    }

    /**
     * Captures one packet object a player is sent, on the connection's event
     * loop. Only PLAY packets of a player in the game are recorded. The
     * packet is encoded once, for the frame; its definition reads the object
     * itself.
     */
    private void capture(Channel channel, net.minecraft.network.Connection connection, Object message) {
        if (state.get() != State.INSTALLED
                || !(message instanceof net.minecraft.network.protocol.Packet<?>)
                || !(connection.getPacketListener()
                        instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game)) {
            return;
        }
        try {
            UUID connectionId = game.player.getUUID();
            String connectionName = game.player.getGameProfile().name();
            PaperConnectionAccessor.WirePacket wirePacket = accessor.inspectAndEncode(
                    new PaperConnectionAccessor.ConnectionHandle(connectionId, channel), message, registry);
            if (wirePacket.phase() != PacketPhase.PLAY) {
                return;
            }
            PacketPhase phase = wirePacket.phase();
            int packetId = wirePacket.packetId();
            PacketDescriptor descriptor = registry.find(
                            phase, PacketDescriptor.Direction.CLIENTBOUND, packetId)
                    .orElseThrow(() -> incompatible(
                            "clientbound packet is not in the verified registry: " + phase + ':' + packetId,
                            null));
            if (!registry.captureAllowed(phase, PacketDescriptor.Direction.CLIENTBOUND, packetId)) {
                return;
            }

            byte[] payload = wirePacket.payload();
            captureOrder.publish(point -> {
                CaptureContext context;
                boolean publishObserved = true;
                ClientboundPacketDefinition<?> definition = Paper26_2ClientboundPacketCatalog.catalog()
                        .find(packetId)
                        .orElseThrow(() -> incompatible(
                                "PLAY packet has no definition for ID " + packetId, null));
                context = definition.captureContext(
                        message, descriptor, connectionId, captureState, point.serverTick());
                if (definition.worldWide()) {
                    context = context.asWorldWide();
                }
                if (!definition.distribution().observable()) {
                    context = context.withOrigin(CaptureOrigin.PLAYER_ONLY);
                }
                if (definition.recipientState().describesRecipient(message)) {
                    // The recipient's own state: its inventory, health or
                    // experience. It belongs to the recipient's state track.
                    context = context.withSubject(new CaptureSubject(
                            TrackSubject.player(connectionId), connectionName));
                }
                if (definition.suppressesRecipientState(
                        message, captureState, point.serverTick())) {
                    publishObserved = false;
                } else if (definition.recordedFromPlayers()) {
                    // Each recording merges the copies other recipients
                    // received; see RecipientCopy.
                    RecipientCopies copies = definition.recipientCopies();
                    if (copies.fromSceneOnly()) {
                        publishObserved = false;
                    } else {
                        Optional<Object> identity = copies.identity(message, payload);
                        if (identity.isPresent()) {
                            context = context.asRecipientCopy(
                                    new RecipientCopy(connectionId, identity.get()));
                        }
                    }
                }
                CaptureBridge.PacketSink currentSink = sink;
                if (publishObserved && currentSink != null) {
                    currentSink.accept(new CaptureBridge.CapturePacket(
                            point.captureTimeNanos(),
                            point.serverTick(),
                            point.sequence(),
                            phase,
                            packetId,
                            payload,
                            context));
                }
            });
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    /**
     * Publishes one packet of an Argus scene. The scene is already merged, so
     * neither recipient suppression nor copy deduplication applies; the
     * packet definition still supplies the capture context.
     */
    private void publishObserved(
            UUID observationId, UUID sceneId, String world, Object packet) {
        CaptureBridge.PacketSink currentSink = sink;
        if (state.get() != State.INSTALLED
                || currentSink == null
                || !currentSink.hasActiveSinks()) {
            return;
        }
        if (!(packet instanceof net.minecraft.network.protocol.Packet<?> nativePacket)) {
            throw incompatible("Argus packet is not a Paper 26.2 packet", null);
        }
        Optional<Paper26_2NativePacketCodec.EncodedStatePacket> observed =
                stateCodec.encodeObserved(nativePacket);
        if (observed.isEmpty()) {
            return;
        }
        Paper26_2NativePacketCodec.EncodedStatePacket encoded = observed.get();
        ClientboundPacketDefinition<?> definition = Paper26_2ClientboundPacketCatalog.catalog()
                .find(encoded.packetId())
                .orElseThrow(() -> incompatible(
                        "PLAY packet has no definition for ID " + encoded.packetId(), null));
        // Definitions key packet-owned state by connection. A scene takes
        // the place of a connection that stays in one world.
        captureState.world(sceneId, world);
        captureOrder.publish(point -> currentSink.accept(new CaptureBridge.CapturePacket(
                point.captureTimeNanos(),
                point.serverTick(),
                point.sequence(),
                PacketPhase.PLAY,
                encoded.packetId(),
                encoded.payload(),
                definition.captureContext(
                                nativePacket, encoded.descriptor(), sceneId,
                                captureState, point.serverTick())
                        .observedBy(observationId))));
    }

    /** Stops the eyes on the server thread, where the server registered them. */
    private void closeArgus() {
        BukkitTask task = argusTask;
        argusTask = null;
        if (task != null) {
            task.cancel();
        }
        if (argus == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            argus.close();
        } else if (owner.isEnabled()) {
            Bukkit.getScheduler().runTask(owner, argus::close);
        }
    }

    /**
     * Installs the clientbound tap and the serverbound receiver on every
     * player connection, present and future. Neither changes, drops or
     * delays a packet.
     */
    private void installChannelHandlers() {
        if (owner == null) {
            return;
        }
        io.papermc.paper.network.ChannelInitializeListenerHolder.addListener(
                SERVERBOUND_RECEIVER_KEY, this::installChannelHandlers);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Channel channel = accessor.connectionFor(player).channel();
            channel.eventLoop().execute(() -> installChannelHandlers(channel));
        }
    }

    private void installChannelHandlers(Channel channel) {
        try {
            var pipeline = channel.pipeline();
            if (!(pipeline.get(PipelineNames.PACKET_HANDLER) instanceof net.minecraft.network.Connection connection)) {
                return;
            }
            ClientboundTap.install(pipeline, new ClientboundTap(
                    Paper26_2ArgusPlatform::unbundlePacket,
                    message -> capture(channel, connection, message),
                    () -> forget(connection),
                    this::fail));
            if (actorEffects != null && pipeline.get(Paper26_2ServerboundReceiver.HANDLER_NAME) == null) {
                pipeline.addBefore(
                        PipelineNames.PACKET_HANDLER,
                        Paper26_2ServerboundReceiver.HANDLER_NAME,
                        new Paper26_2ServerboundReceiver(
                                connection, this::installed, actorEffects, this::fail));
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    /** Drops the capture state of a connection that closed. */
    private void forget(net.minecraft.network.Connection connection) {
        if (connection.getPacketListener()
                instanceof net.minecraft.server.network.ServerGamePacketListenerImpl game) {
            captureState.removeConnection(game.player.getUUID());
        }
    }

    private void uninstallChannelHandlers() {
        if (owner == null) {
            return;
        }
        io.papermc.paper.network.ChannelInitializeListenerHolder.removeListener(SERVERBOUND_RECEIVER_KEY);
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                PaperConnectionAccessor.ConnectionHandle connection = accessor.connectionFor(player);
                accessor.uninstallHandler(connection, PipelineNames.CLIENTBOUND_TAP);
                accessor.uninstallHandler(connection, Paper26_2ServerboundReceiver.HANDLER_NAME);
            }
        } catch (Throwable ignored) {
            // Handlers left on closing connections pass every packet through.
        }
    }

    /**
     * Publishes one packet of a subject's state that Argus read on the server
     * thread. It belongs to the recording of the watch and to no world.
     */
    private void publishSubjectState(UUID observationId, CaptureSubject subject, Object packet) {
        publishState(observationId, packet, context -> context.withSubject(subject), Optional.empty());
    }

    /**
     * Publishes one packet with the state of a block that Argus read on the
     * server thread. It belongs to the block state track of its world.
     */
    private void publishBlockState(UUID observationId, String world, Object packet) {
        publishState(observationId, packet, CaptureContext::asBlockState,
                Optional.of(net.kyori.adventure.key.Key.key(world)));
    }

    private void publishState(
            UUID observationId,
            Object packet,
            java.util.function.UnaryOperator<CaptureContext> route,
            Optional<net.kyori.adventure.key.Key> world) {
        CaptureBridge.PacketSink currentSink = sink;
        if (state.get() != State.INSTALLED || currentSink == null || !currentSink.hasActiveSinks()) {
            return;
        }
        if (!(packet instanceof net.minecraft.network.protocol.Packet<?> nativePacket)) {
            throw incompatible("state is not a Paper 26.2 packet", null);
        }
        Paper26_2NativePacketCodec.EncodedStatePacket encoded = stateCodec.encodeState(nativePacket);
        CaptureContext context = new CaptureContext(
                encoded.descriptor().disposition(),
                world,
                Optional.empty(),
                Optional.empty(),
                Optional.empty())
                .observedBy(observationId);
        CaptureContext routed = route.apply(context);
        captureOrder.publish(point -> currentSink.accept(new CaptureBridge.CapturePacket(
                point.captureTimeNanos(),
                point.serverTick(),
                point.sequence(),
                PacketPhase.PLAY,
                encoded.packetId(),
                encoded.payload(),
                routed)));
    }

    /**
     * Publishes a clientbound packet the server sent to everyone except the
     * acting player. Its definition supplies the capture context; the
     * packet is one only players receive, so every recording may accept it.
     */
    private void publishActorEffect(Object packet, UUID actorConnectionId) {
        CaptureBridge.PacketSink currentSink = sink;
        if (state.get() != State.INSTALLED || currentSink == null || !currentSink.hasActiveSinks()) {
            return;
        }
        if (!(packet instanceof net.minecraft.network.protocol.Packet<?> nativePacket)) {
            throw incompatible("actor effect is not a Paper 26.2 packet", null);
        }
        Paper26_2NativePacketCodec.EncodedStatePacket encoded = stateCodec.encodeState(nativePacket);
        ClientboundPacketDefinition<?> definition = Paper26_2ClientboundPacketCatalog.catalog()
                .find(encoded.packetId())
                .orElseThrow(() -> incompatible(
                        "PLAY packet has no definition for ID " + encoded.packetId(), null));
        captureOrder.publish(point -> currentSink.accept(new CaptureBridge.CapturePacket(
                point.captureTimeNanos(),
                point.serverTick(),
                point.sequence(),
                PacketPhase.PLAY,
                encoded.packetId(),
                encoded.payload(),
                definition.captureContext(
                                nativePacket, encoded.descriptor(), actorConnectionId,
                                captureState, point.serverTick())
                        .withOrigin(CaptureOrigin.PLAYER_ONLY))));
    }

    private void fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (handlerStateLock) {
            State current = state.get();
            if (current == State.FAILED || current == State.UNINSTALLED) {
                return;
            }
            // FAILED means a capture-integrity invariant was lost. Active
            // recordings must observe this and cannot continue as available.
            state.set(State.FAILED);
        }

        uninstallChannelHandlers();
        closeArgus();
        captureState.clear();

        if (failureReported.compareAndSet(false, true)) {
            CaptureBridge.FailureHandler currentFailureHandler = failureHandler;
            if (currentFailureHandler != null) {
                try {
                    currentFailureHandler.onFailure(failure);
                } catch (Throwable ignored) {
                    // A diagnostic failure handler must not escape a callback.
                }
            }
        }
    }

    /** Allocates an exclusive checkpoint boundary in capture publication order. */
    public CheckpointEncoder.CapturePoint checkpointCapturePoint() {
        return captureOrder.barrier();
    }

    /** Seeds the relative-movement baseline from the authoritative checkpoint snapshot. */
    public void seedCheckpointEntityPosition(
            int entityId, String worldKey, double x, double y, double z) {
        captureState.seedEntityPosition(entityId, worldKey, x, y, z);
    }

    private boolean hasActiveRecording() {
        CaptureBridge.PacketSink currentSink = sink;
        return state.get() == State.INSTALLED
                && currentSink != null
                && currentSink.hasActiveSinks();
    }

    /** Seeds context for connections already online when capture is installed. */
    private void seedOnlineConnections() {
        if (owner == null) {
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw incompatible("Paper capture must be installed on the server thread", null);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID connectionId = player.getUniqueId();
            Key world = player.getWorld().getKey();
            int entityId = player.getEntityId();
            var location = player.getLocation();
            captureState.world(connectionId, world.toString());
            captureState.connectionEntityId(connectionId, entityId);
            captureState.entityPosition(
                    connectionId, entityId, world.toString(),
                    location.getX(), location.getY(), location.getZ());
        }
    }

    private static PacketRegistry verifyRegistry(PacketRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (!(registry instanceof Paper26_2PacketRegistry)) {
            throw incompatible("Paper capture requires the Paper 26.2 packet registry", null);
        }
        String liveFingerprint = Paper26_2PacketRegistry.discover().fingerprint();
        if (!liveFingerprint.equals(registry.fingerprint())) {
            throw incompatible("Paper packet registry fingerprint changed", null);
        }
        return registry;
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    /** Status values document whether capture is trusted, failed or shut down. */
    private enum State {
        /** No connection handler is installed. */
        NEW,
        /** The connection handlers and Argus are active. */
        INSTALLED,
        /** Capture integrity failed; active recording must fail. */
        FAILED,
        /** Expected terminal lifecycle state after uninstall. */
        UNINSTALLED
    }
}
