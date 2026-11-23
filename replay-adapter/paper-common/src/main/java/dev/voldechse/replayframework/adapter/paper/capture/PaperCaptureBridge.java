package dev.voldechse.replayframework.adapter.paper.capture;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.CaptureOrigin;
import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.RecipientCopy;
import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.argus.Argus;
import dev.voldechse.replayframework.adapter.paper.argus.ArgusPublisher;
import dev.voldechse.replayframework.adapter.paper.channel.ClientboundTap;
import dev.voldechse.replayframework.adapter.paper.channel.PipelineNames;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.RecipientCopies;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.TrackSubject;
import io.netty.channel.Channel;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Capture shared by every Paper version, for all active recordings at once.
 * Session filtering remains in the core router.
 *
 * <p>Every player connection gets a {@link ClientboundTap} that sees each
 * packet object the player is sent, before it is encoded, plus the version's
 * own handlers, such as the receiver of serverbound packets. Argus delivers
 * the scene of its eyes on the server thread. Every packet passes its
 * definition for its capture context and is published through one
 * {@link CaptureOrder}, so time, tick and sequence form one order across all
 * sources.</p>
 *
 * <p>What depends on the Minecraft version comes from {@link CaptureHooks}.</p>
 */
public final class PaperCaptureBridge implements CaptureBridge {

    private final JavaPlugin owner;
    private final CaptureHooks hooks;
    private final PacketRegistry registry;
    private final CaptureStateIndex captureState = new CaptureStateIndex();
    private final CaptureOrder captureOrder;
    private final Argus argus;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final Object handlerStateLock = new Object();
    private final CaptureHooks.CaptureCallbacks callbacks = new CaptureHooks.CaptureCallbacks() {
        @Override
        public boolean installed() {
            return PaperCaptureBridge.this.installed();
        }

        @Override
        public boolean recording() {
            return hasActiveRecording();
        }

        @Override
        public void publishActorEffect(Object packet, UUID actor) {
            PaperCaptureBridge.this.publishActorEffect(packet, actor);
        }

        @Override
        public void fail(Throwable failure) {
            PaperCaptureBridge.this.fail(failure);
        }
    };

    private volatile BukkitTask argusTask;
    private volatile CaptureBridge.PacketSink sink;
    private volatile CaptureBridge.FailureHandler failureHandler;

    /**
     * @param owner the plugin that schedules Argus
     * @param hooks the version-specific half of capture
     */
    public PaperCaptureBridge(JavaPlugin owner, CaptureHooks hooks) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.registry = Objects.requireNonNull(hooks.registry(), "registry");
        this.captureOrder = new CaptureOrder(System::nanoTime, hooks::serverTick);
        this.argus = new Argus(
                hooks.argus(),
                new ArgusPublisher() {
                    @Override
                    public void publish(UUID observationId, UUID sceneId, String world, Object packet) {
                        publishObserved(observationId, sceneId, world, packet);
                    }

                    @Override
                    public void publishState(UUID observationId, CaptureSubject subject, Object packet) {
                        PaperCaptureBridge.this.publishState(
                                observationId, packet, context -> context.withSubject(subject), Optional.empty());
                    }

                    @Override
                    public void publishBlockState(UUID observationId, String world, Object packet) {
                        PaperCaptureBridge.this.publishState(
                                observationId, packet, CaptureContext::asBlockState, Optional.of(Key.key(world)));
                    }

                    @Override
                    public void sceneClosed(UUID sceneId) {
                        captureState.removeConnection(sceneId);
                    }
                },
                hooks::serverTick,
                this::fail);
    }

    /** Returns Argus, the virtual-observer source of this bridge. */
    public SceneObservation sceneObservation() {
        return argus;
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
            argusTask = Bukkit.getScheduler().runTaskTimer(owner, argus::tick, 1L, 1L);
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

    /** Allocates an exclusive checkpoint boundary in capture publication order. */
    public CheckpointEncoder.CapturePoint checkpointCapturePoint() {
        return captureOrder.barrier();
    }

    /** Seeds the relative-movement baseline from the authoritative checkpoint snapshot. */
    public void seedCheckpointEntityPosition(int entityId, String worldKey, double x, double y, double z) {
        captureState.seedEntityPosition(entityId, worldKey, x, y, z);
    }

    /**
     * Captures one packet object a player is sent, on the connection's event
     * loop. Only PLAY packets of a player in the game are recorded. The
     * packet is encoded once, for the frame; its definition reads the object
     * itself.
     */
    private void capture(Channel channel, Object message) {
        if (state.get() != State.INSTALLED) {
            return;
        }
        Optional<CaptureHooks.ConnectionPlayer> player = hooks.player(channel);
        if (player.isEmpty()) {
            return;
        }
        try {
            Optional<PacketCodec.EncodedPacket> encoded = hooks.encodeSent(channel, message);
            if (encoded.isEmpty()) {
                return;
            }
            UUID connectionId = player.get().id();
            String connectionName = player.get().name();
            int packetId = encoded.get().packetId();
            byte[] payload = encoded.get().payload();
            PacketDescriptor descriptor = descriptor(packetId);
            if (!registry.captureAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, packetId)) {
                return;
            }
            ClientboundPacketDefinition<?> definition = definition(packetId);
            captureOrder.publish(point -> {
                CaptureContext context = definition.captureContext(
                        message, descriptor, connectionId, captureState, point.serverTick());
                boolean publish = true;
                if (definition.worldWide()) {
                    context = context.asWorldWide();
                }
                if (!definition.distribution().observable()) {
                    context = context.withOrigin(CaptureOrigin.PLAYER_ONLY);
                }
                if (definition.recipientState().describesRecipient(message)) {
                    // The recipient's own state, such as its health. It
                    // belongs to the recipient's state track.
                    context = context.withSubject(new CaptureSubject(
                            TrackSubject.player(connectionId), connectionName));
                }
                if (definition.suppressesRecipientState(message, captureState, point.serverTick())) {
                    publish = false;
                } else if (definition.recordedFromPlayers()) {
                    // Each recording merges the copies other recipients
                    // received; see RecipientCopy.
                    RecipientCopies copies = definition.recipientCopies();
                    if (copies.fromSceneOnly()) {
                        publish = false;
                    } else {
                        Optional<Object> identity = copies.identity(message, payload);
                        if (identity.isPresent()) {
                            context = context.asRecipientCopy(new RecipientCopy(connectionId, identity.get()));
                        }
                    }
                }
                CaptureBridge.PacketSink currentSink = sink;
                if (publish && currentSink != null) {
                    currentSink.accept(new CaptureBridge.CapturePacket(
                            point.captureTimeNanos(), point.serverTick(), point.sequence(),
                            PacketPhase.PLAY, packetId, payload, context));
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
    private void publishObserved(UUID observationId, UUID sceneId, String world, Object packet) {
        CaptureBridge.PacketSink currentSink = sink;
        if (state.get() != State.INSTALLED || currentSink == null || !currentSink.hasActiveSinks()) {
            return;
        }
        Optional<PacketCodec.EncodedPacket> observed = hooks.encodeObserved(packet);
        if (observed.isEmpty()) {
            return;
        }
        PacketCodec.EncodedPacket encoded = observed.get();
        PacketDescriptor descriptor = descriptor(encoded.packetId());
        ClientboundPacketDefinition<?> definition = definition(encoded.packetId());
        // Definitions key packet-owned state by connection. A scene takes
        // the place of a connection that stays in one world.
        captureState.world(sceneId, world);
        captureOrder.publish(point -> currentSink.accept(new CaptureBridge.CapturePacket(
                point.captureTimeNanos(), point.serverTick(), point.sequence(),
                PacketPhase.PLAY, encoded.packetId(), encoded.payload(),
                definition.captureContext(packet, descriptor, sceneId, captureState, point.serverTick())
                        .observedBy(observationId))));
    }

    /**
     * Publishes one packet of a subject's or a block's state that Argus read
     * on the server thread. It belongs to the recording of the watch.
     */
    private void publishState(
            UUID observationId, Object packet, UnaryOperator<CaptureContext> route, Optional<Key> world) {
        CaptureBridge.PacketSink currentSink = sink;
        if (state.get() != State.INSTALLED || currentSink == null || !currentSink.hasActiveSinks()) {
            return;
        }
        PacketCodec.EncodedPacket encoded = hooks.encodeState(packet);
        PacketDescriptor descriptor = descriptor(encoded.packetId());
        CaptureContext context = route.apply(new CaptureContext(
                descriptor.disposition(), world, Optional.empty(), Optional.empty(), Optional.empty())
                .observedBy(observationId));
        captureOrder.publish(point -> currentSink.accept(new CaptureBridge.CapturePacket(
                point.captureTimeNanos(), point.serverTick(), point.sequence(),
                PacketPhase.PLAY, encoded.packetId(), encoded.payload(), context)));
    }

    /** Publishes an effect the server sent everyone except its actor, rebuilt for the recording. */
    private void publishActorEffect(Object packet, UUID actorConnectionId) {
        CaptureBridge.PacketSink currentSink = sink;
        if (state.get() != State.INSTALLED || currentSink == null || !currentSink.hasActiveSinks()) {
            return;
        }
        PacketCodec.EncodedPacket encoded = hooks.encodeState(packet);
        PacketDescriptor descriptor = descriptor(encoded.packetId());
        ClientboundPacketDefinition<?> definition = definition(encoded.packetId());
        captureOrder.publish(point -> currentSink.accept(new CaptureBridge.CapturePacket(
                point.captureTimeNanos(), point.serverTick(), point.sequence(),
                PacketPhase.PLAY, encoded.packetId(), encoded.payload(),
                definition.captureContext(packet, descriptor, actorConnectionId, captureState, point.serverTick())
                        .withOrigin(CaptureOrigin.PLAYER_ONLY))));
    }

    private PacketDescriptor descriptor(int packetId) {
        return registry.find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, packetId)
                .orElseThrow(() -> incompatible(
                        "clientbound packet is not in the verified registry: PLAY:" + packetId, null));
    }

    private ClientboundPacketDefinition<?> definition(int packetId) {
        return hooks.definitions().find(packetId)
                .orElseThrow(() -> incompatible("PLAY packet has no definition for ID " + packetId, null));
    }

    /** Stops the eyes on the server thread, where the server registered them. */
    private void closeArgus() {
        BukkitTask task = argusTask;
        argusTask = null;
        if (task != null) {
            task.cancel();
        }
        if (Bukkit.isPrimaryThread()) {
            argus.close();
        } else if (owner.isEnabled()) {
            Bukkit.getScheduler().runTask(owner, argus::close);
        }
    }

    /**
     * Installs the clientbound tap and the version's handlers on every player
     * connection, present and future. None of them changes, drops or delays
     * a packet.
     */
    private void installChannelHandlers() {
        hooks.watchConnections(this::installChannelHandlers);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Channel channel = hooks.channel(player);
            channel.eventLoop().execute(() -> installChannelHandlers(channel));
        }
    }

    private void installChannelHandlers(Channel channel) {
        try {
            ClientboundTap.install(channel.pipeline(), new ClientboundTap(
                    hooks::unbundle,
                    message -> capture(channel, message),
                    () -> hooks.player(channel).ifPresent(player -> captureState.removeConnection(player.id())),
                    this::fail));
            hooks.installHandlers(channel, callbacks);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void uninstallChannelHandlers() {
        hooks.stopWatchingConnections();
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Channel channel = hooks.channel(player);
                channel.eventLoop().execute(() -> {
                    try {
                        if (channel.pipeline().get(PipelineNames.CLIENTBOUND_TAP) != null) {
                            channel.pipeline().remove(PipelineNames.CLIENTBOUND_TAP);
                        }
                        hooks.uninstallHandlers(channel);
                    } catch (Throwable ignored) {
                        // Handlers left on closing connections pass every packet through.
                    }
                });
            }
        } catch (Throwable ignored) {
            // Handlers left on closing connections pass every packet through.
        }
    }

    private boolean hasActiveRecording() {
        CaptureBridge.PacketSink currentSink = sink;
        return state.get() == State.INSTALLED && currentSink != null && currentSink.hasActiveSinks();
    }

    /** Seeds context for connections already online when capture is installed. */
    private void seedOnlineConnections() {
        if (!Bukkit.isPrimaryThread()) {
            throw incompatible("Paper capture must be installed on the server thread", null);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID connectionId = player.getUniqueId();
            String world = player.getWorld().getKey().toString();
            int entityId = player.getEntityId();
            var location = player.getLocation();
            captureState.world(connectionId, world);
            captureState.connectionEntityId(connectionId, entityId);
            captureState.entityPosition(
                    connectionId, entityId, world, location.getX(), location.getY(), location.getZ());
        }
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

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    /** Whether capture is trusted, failed or shut down. */
    private enum State {
        /** No connection handler is installed. */
        NEW,
        /** The connection handlers and Argus are active. */
        INSTALLED,
        /** Capture integrity failed; active recordings must fail. */
        FAILED,
        /** Expected terminal lifecycle state after uninstall. */
        UNINSTALLED
    }
}
