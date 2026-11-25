package dev.voldechse.replayframework.adapter.paper.playback;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.PlaybackEmissionMode;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.channel.PipelineNames;
import dev.voldechse.replayframework.adapter.paper.channel.ReplayChannelWriter;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Playback to one viewer, shared by every Paper version.
 *
 * <p>Frames pass a fixed pipeline: the registry decides whether a frame may
 * be replayed at all, the scene notes it, the world filter keeps frames of
 * worlds the viewer does not watch, the rewriter moves it into the session's
 * identity space, and the {@link ReplayChannelWriter} writes it past the
 * server's encoder. A {@link ViewerOutboundQueue} runs all of this on the
 * connection's event loop, and a {@link ViewerGate} keeps conflicting live
 * scene packets from the viewer.</p>
 *
 * <p>What depends on the Minecraft version comes from {@link ViewerParts}.</p>
 */
public final class PaperPlaybackBridge implements PlaybackBridge {

    private final Channel channel;
    private final int observerEntityId;
    private final PacketRegistry registry;
    private final ViewerParts parts;
    private final ViewerScene scene;
    private final ViewResetter resetter;
    private final PacketCodec<Object> codec;
    private final ViewerGate gate;
    private final ReplayChannelWriter writer;
    private final ViewerOutboundQueue queue;
    private final AtomicReference<Consumer<Throwable>> failureHandler;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicReference<ViewerRewriter> rewriter = new AtomicReference<>();
    /** The recorded world the viewer watches; only its frames are sent. */
    private final AtomicReference<String> viewedWorld = new AtomicReference<>();
    private volatile Consumer<BlockInteraction> blockInteractions;

    /**
     * Opens the playback boundary of one viewer and installs the gate and
     * the replay writer on the connection's event loop.
     *
     * @param hooks the version-specific half of playback
     * @param viewer the viewer
     * @param failureHandler receives a failure of the playback
     */
    public PaperPlaybackBridge(PlaybackHooks hooks, Player viewer, Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(hooks, "hooks");
        Objects.requireNonNull(viewer, "viewer");
        this.observerEntityId = viewer.getEntityId();
        this.registry = Objects.requireNonNull(hooks.registry(), "registry");
        this.failureHandler = new AtomicReference<>(Objects.requireNonNull(failureHandler, "failureHandler"));
        this.channel = Objects.requireNonNull(hooks.channel(viewer), "channel");
        this.parts = hooks.open(viewer, channel, new ViewerParts.Callbacks() {
            @Override
            public void write(Object packet) {
                writeNativePacket(packet);
            }

            @Override
            public void fail(Throwable failure) {
                PaperPlaybackBridge.this.fail(failure);
            }
        });
        this.scene = parts.scene();
        this.resetter = parts.resetter();
        this.codec = parts.codec();
        this.writer = new ReplayChannelWriter(channel, this::fail);
        this.queue = new ViewerOutboundQueue(channel, writer::flush, this::isTerminal, this::fail);
        this.gate = new ViewerGate(registry, parts::classify, this::gateReady, this::fail);
        state.set(State.INSTALLING);
        try {
            channel.eventLoop().execute(this::installGate);
            writer.install();
        } catch (Throwable failure) {
            fail(failure);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("playback gate installation failed", failure);
        }
    }

    @Override
    public void setIdentityContext(PlaybackIdentityContext context) {
        Objects.requireNonNull(context, "context");
        requireUsable("setIdentityContext");
        if (!rewriter.compareAndSet(null, parts.rewriter(context, observerEntityId))) {
            throw new IllegalStateException("playback identity context was already bound");
        }
    }

    @Override
    public void setFailureHandler(Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (isTerminal()) {
            throw new IllegalStateException("playback bridge is already closed");
        }
        this.failureHandler.set(failureHandler);
    }

    @Override
    public List<ObserverAnchor> observerAnchors(List<RawPacketFrame> checkpointFrames) {
        return scene.observerAnchors(Objects.requireNonNull(checkpointFrames, "checkpointFrames"));
    }

    @Override
    public void activateReplayIsolation() {
        requireUsable("activateReplayIsolation");
        gate.activateIsolation();
    }

    @Override
    public void send(RawPacketFrame frame) {
        sendBatch(List.of(Objects.requireNonNull(frame, "frame")), PlaybackEmissionMode.LIVE);
    }

    @Override
    public void sendBatch(List<RawPacketFrame> frames, PlaybackEmissionMode mode) {
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(mode, "mode");
        requireUsable("sendBatch");
        List<Runnable> operations = new ArrayList<>(frames.size());
        for (RawPacketFrame frame : List.copyOf(frames)) {
            if (shouldReplay(frame, mode, registry)) {
                operations.add(() -> processFrame(frame));
            }
        }
        queue.enqueue(operations);
    }

    @Override
    public void resetView() {
        requireUsable("resetView");
        state.updateAndGet(current -> switch (current) {
            case NEW, INSTALLING, OPEN, RESETTING -> State.RESETTING;
            case FAILED, CLOSING, CLOSED -> current;
        });
        queue.enqueue(List.of(() -> {
            // The rebuilt view replaces the live world the viewer may have
            // received after a world change.
            gate.resumeIsolation();
            resetter.reset();
            scene.clear();
            ViewerRewriter current = rewriter.get();
            if (current != null) {
                current.resetView();
            }
            state.compareAndSet(State.RESETTING, State.OPEN);
        }));
    }

    @Override
    public CompletionStage<Void> awaitOutboundIdle() {
        return queue.awaitIdle();
    }

    @Override
    public void discardQueuedReplayPackets() {
        requireUsable("discardQueuedReplayPackets");
        queue.clear(new CancellationException("playback outbound queue was superseded by a seek"));
    }

    @Override
    public void close() {
        State previous = state.getAndSet(State.CLOSING);
        if (previous == State.CLOSED || previous == State.CLOSING) {
            return;
        }
        queue.clear();
        detachView();
        gate.close();
        ViewerRewriter current = rewriter.getAndSet(null);
        if (current != null) {
            current.clear();
        }
        try {
            removeAndWait(ViewerGate.HANDLER_NAME);
            runAndWait(() -> parts.uninstallInteractions(channel));
            writer.uninstall();
        } catch (Throwable ignored) {
            // Channel teardown is best effort during quit and server shutdown.
        }
        state.set(State.CLOSED);
    }

    @Override
    public void viewWorld(String recordedWorld) {
        viewedWorld.set(Objects.requireNonNull(recordedWorld, "recordedWorld"));
    }

    @Override
    public Optional<String> viewedWorld() {
        return Optional.ofNullable(viewedWorld.get());
    }

    @Override
    public List<RecordedPlayer> recordedPlayers() {
        return scene.players();
    }

    @Override
    public List<RecordedPlayer> players(List<RawPacketFrame> frames) {
        return scene.players(frames);
    }

    @Override
    public Optional<RecordedEntity> entity(int clientEntityId) {
        ViewerRewriter current = rewriter.get();
        if (current == null) {
            return Optional.empty();
        }
        OptionalInt recorded = current.recordedEntityId(clientEntityId);
        return recorded.isPresent() ? scene.entity(recorded.getAsInt()) : Optional.empty();
    }

    @Override
    public List<RecordedEntity> entities() {
        String world = viewedWorld.get();
        return world == null ? List.of() : scene.entities(world);
    }

    @Override
    public Optional<SubjectState> decodeState(RecordedSubject subject, List<RawPacketFrame> frames) {
        return scene.decodeState(subject, frames);
    }

    @Override
    public Optional<SubjectState> decodeBlock(BlockPosition position, List<RawPacketFrame> frames) {
        return scene.decodeBlock(position, frames);
    }

    @Override
    public void setBlockInteractionHandler(Consumer<BlockInteraction> handler) {
        this.blockInteractions = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Reads the viewer's use and attack packets aimed at replay entities, and
     * its clicks on recorded containers, and hands them to the playback; the
     * server never sees them.
     */
    @Override
    public void setInteractionHandler(Consumer<EntityInteraction> handler) {
        Objects.requireNonNull(handler, "handler");
        ViewerParts.Interactions interactions = new ViewerParts.Interactions() {
            @Override
            public Optional<RecordedEntity> entity(int clientEntityId) {
                return PaperPlaybackBridge.this.entity(clientEntityId);
            }

            @Override
            public void entityInteracted(EntityInteraction interaction) {
                handler.accept(interaction);
            }

            @Override
            public Optional<BlockInteraction> block(BlockPosition position, EquipmentSlot hand) {
                return blockInteraction(position, hand);
            }

            @Override
            public void blockInteracted(BlockInteraction interaction) {
                Consumer<BlockInteraction> current = blockInteractions;
                if (current != null) {
                    current.accept(interaction);
                }
            }

            @Override
            public void fail(Throwable failure) {
                PaperPlaybackBridge.this.fail(failure);
            }
        };
        channel.eventLoop().execute(() -> parts.installInteractions(channel, interactions));
    }

    /** The viewer's click on a recorded container of the viewed world, when there is one at the position. */
    private Optional<BlockInteraction> blockInteraction(BlockPosition position, EquipmentSlot hand) {
        String world = viewedWorld.get();
        if (world == null || blockInteractions == null) {
            return Optional.empty();
        }
        return scene.container(world, position)
                .map(block -> new BlockInteraction(Key.key(world), position, block, hand));
    }

    /** Drops every recorded packet that has no explicit safe replay contract. */
    public static boolean shouldReplay(RawPacketFrame frame, PlaybackEmissionMode mode, PacketRegistry registry) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(registry, "registry");
        if (frame.phase() != PacketPhase.PLAY) {
            return false;
        }
        PacketDescriptor descriptor = registry.find(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .orElse(null);
        return descriptor != null
                && descriptor.replayable()
                && (mode == PlaybackEmissionMode.LIVE || descriptor.checkpointRelevant())
                && registry.replayAllowed(frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId());
    }

    private void processFrame(RawPacketFrame frame) {
        if (isTerminal()) {
            return;
        }
        PacketDescriptor descriptor = registry.find(
                        frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, frame.packetId())
                .orElseThrow(() -> incompatible("replay packet descriptor disappeared from the verified registry"));
        // Players are followed in every world, before the world filter.
        scene.observe(frame, descriptor.typeName());
        // A client can only apply packets of the world it is in; a chunk of
        // another dimension has a different height and breaks the connection.
        String world = viewedWorld.get();
        if (world != null && !frame.appliesTo(world)) {
            return;
        }
        ViewerRewriter current = rewriter.get();
        if (current == null) {
            throw incompatible("playback identity context is not bound");
        }
        PacketRewriteResult result = current.rewrite(frame, descriptor, codec);
        switch (result.decision()) {
            case FAIL -> throw incompatible("replay packet could not be isolated: " + result.reason());
            case DROP_LOCAL -> { }
            case SEND -> {
                Object packet = result.packet().orElseThrow();
                resetter.observe(packet);
                PacketDescriptor output = result.outputPacketId()
                        .map(packetId -> registry.find(frame.phase(), PacketDescriptor.Direction.CLIENTBOUND, packetId)
                                .filter(PacketDescriptor::replayable)
                                .filter(candidate -> registry.replayAllowed(
                                        candidate.phase(), candidate.direction(), candidate.packetId()))
                                .orElseThrow(() -> incompatible(
                                        "rewritten packet output ID is not permitted by the verified registry")))
                        .orElse(descriptor);
                writer.write(current.encode(packet, output, codec));
            }
        }
    }

    /** Writes a packet the version's parts built, such as a removal, after the registry allowed it. */
    private void writeNativePacket(Object packet) {
        PacketCodec.EncodedPacket encoded = codec.encode(packet);
        if (encoded.phase() != PacketPhase.PLAY || encoded.direction() != PacketDescriptor.Direction.CLIENTBOUND) {
            throw incompatible("playback packet is not PLAY clientbound");
        }
        PacketDescriptor descriptor = registry.find(encoded.phase(), encoded.direction(), encoded.packetId())
                .orElseThrow(() -> incompatible("playback packet is not in the verified registry"));
        if (!descriptor.replayable()
                || !registry.replayAllowed(encoded.phase(), encoded.direction(), encoded.packetId())) {
            throw incompatible("playback packet is blocked by the verified registry");
        }
        writer.write(encoded);
    }

    /**
     * Removes what the replay added to the viewer's client. The removal runs
     * on the connection's event loop, after the frame batch that may still be
     * running there and before anything the integration sends after closing,
     * such as moving the viewer back. Best effort: the viewer may be gone.
     */
    private void detachView() {
        if (!channel.isActive()) {
            return;
        }
        try {
            channel.eventLoop().execute(() -> {
                try {
                    resetter.detach();
                    writer.flush();
                } catch (Throwable ignored) {
                    // The viewer disconnected while the playback closed.
                }
            });
        } catch (Throwable ignored) {
            // A closed event loop is an expected disconnect or shutdown path.
        }
    }

    private void installGate() {
        try {
            if (channel.pipeline().get(ViewerGate.HANDLER_NAME) != null) {
                throw incompatible("reserved playback handler name is already in use");
            }
            if (channel.pipeline().get(PipelineNames.ENCODER) == null) {
                throw incompatible("viewer connection has no packet encoder");
            }
            channel.pipeline().addAfter(PipelineNames.ENCODER, ViewerGate.HANDLER_NAME, gate);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void gateReady() {
        state.compareAndSet(State.INSTALLING, State.OPEN);
    }

    private void removeAndWait(String handlerName) {
        runAndWait(() -> {
            if (channel.pipeline().get(handlerName) != null) {
                channel.pipeline().remove(handlerName);
            }
        });
    }

    private void runAndWait(Runnable action) {
        if (channel.eventLoop().inEventLoop()) {
            action.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        channel.eventLoop().execute(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        done.join();
    }

    private void requireUsable(String operation) {
        State current = state.get();
        if (current == State.FAILED || current == State.CLOSING || current == State.CLOSED) {
            throw new IllegalStateException("playback bridge cannot " + operation + " in state " + current);
        }
    }

    private boolean isTerminal() {
        State current = state.get();
        return current == State.FAILED || current == State.CLOSING || current == State.CLOSED;
    }

    private void fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        State current = state.getAndSet(State.FAILED);
        if (current == State.CLOSED || current == State.CLOSING) {
            return;
        }
        gate.close();
        try {
            removeAndWait(ViewerGate.HANDLER_NAME);
            writer.uninstall();
        } catch (Throwable ignored) {
            // Failure cleanup is best effort and must not escape Netty.
        }
        state.set(State.CLOSED);
        if (failureReported.compareAndSet(false, true)) {
            try {
                failureHandler.get().accept(failure);
            } catch (Throwable ignored) {
                // A diagnostic callback must not terminate the caller or loop.
            }
        }
    }

    private static IncompatibleAdapterException incompatible(String message) {
        return new IncompatibleAdapterException(message);
    }

    /** The bridge's trust and lifecycle. */
    private enum State {
        NEW,
        INSTALLING,
        OPEN,
        RESETTING,
        FAILED,
        CLOSING,
        CLOSED
    }
}
