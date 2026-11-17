package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapter boundary for writing validated replay packets to one prepared viewer.
 */
public interface PlaybackBridge {

    /** Binds the bridge to one deterministic, session-local identity map. */
    void setIdentityContext(PlaybackIdentityContext context);

    /** Routes asynchronous adapter failures back into the owning playback session. */
    default void setFailureHandler(Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
    }

    /**
     * Decodes ordered observer anchors from an initial checkpoint. Native
     * packet interpretation remains owned by the versioned adapter.
     */
    default List<ObserverAnchor> observerAnchors(List<RawPacketFrame> checkpointFrames) {
        Objects.requireNonNull(checkpointFrames, "checkpointFrames");
        return List.of();
    }

    /** Activates live replay-packet suppression after observer placement. */
    default void activateReplayIsolation() {
    }

    /**
     * Sends one validated clientbound replay frame.
     *
     * @param frame raw frame from the matching adapter format
     * @throws IncompatibleAdapterException when the frame is unknown or blocked
     * @throws IllegalStateException after {@link #close()}
     */
    void send(RawPacketFrame frame);

    /**
     * Enqueues an ordered batch for one emission purpose. Adapters may filter
     * transient packets during reconstruction and must retain bounded outbound
     * processing. The default preserves compatibility with bridges that have
     * no adapter-specific batching implementation.
     *
     * @param frames ordered frames from the matching adapter format
     * @param mode live playback or state reconstruction
     */
    default void sendBatch(List<RawPacketFrame> frames, PlaybackEmissionMode mode) {
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(mode, "mode");
        for (RawPacketFrame frame : List.copyOf(frames)) {
            send(frame);
        }
    }

    /**
     * Clears the current replay view using adapter-valid clientbound packets.
     *
     * @throws IllegalStateException after {@link #close()}
     */
    void resetView();

    /**
     * Chooses the recorded world the viewer watches. A recording can span
     * several worlds, and a client can only apply the frames of one: a chunk
     * of another dimension has a different height and breaks the client's
     * connection. Frames of other worlds are not sent; frames of no world,
     * such as chat, always are. The viewer's own live world does not matter,
     * because playback places the viewer in a world of its own.
     */
    default void viewWorld(String recordedWorld) {
    }

    /** Returns the recorded world the viewer watches, when one was chosen. */
    default java.util.Optional<String> viewedWorld() {
        return java.util.Optional.empty();
    }

    /**
     * Returns the players of the replay at the current playback position,
     * across all recorded worlds. The default bridge knows none.
     */
    /**
     * Builds the state of a subject from the frames of its state track: the
     * checkpoint at the playback position's barrier, then the frames up to
     * the position. The default bridge knows no state.
     */
    default java.util.Optional<dev.voldechse.replayframework.api.playback.SubjectState> decodeState(
            dev.voldechse.replayframework.api.playback.RecordedSubject subject,
            List<RawPacketFrame> frames) {
        return java.util.Optional.empty();
    }

    /** What the scene shows of the entity the viewer's client knows by this ID. */
    default java.util.Optional<dev.voldechse.replayframework.api.playback.RecordedEntity> entity(
            int clientEntityId) {
        return java.util.Optional.empty();
    }

    /** Every entity of the viewed world at the current position. */
    default java.util.List<dev.voldechse.replayframework.api.playback.RecordedEntity> entities() {
        return java.util.List.of();
    }

    /**
     * Receives the viewer's interactions with replay entities. The bridge
     * reads them from the viewer's serverbound packets and keeps them from
     * the server, which knows no such entity.
     */
    default void setInteractionHandler(Consumer<EntityInteraction> handler) {
    }

    /**
     * A viewer's interaction with a replay entity.
     *
     * @param entity what the scene shows of the entity
     * @param attack whether the viewer attacked instead of using it
     * @param hand the hand the viewer used
     */
    record EntityInteraction(
            dev.voldechse.replayframework.api.playback.RecordedEntity entity,
            boolean attack,
            org.bukkit.inventory.EquipmentSlot hand) {
        public EntityInteraction {
            java.util.Objects.requireNonNull(entity, "entity");
            java.util.Objects.requireNonNull(hand, "hand");
        }
    }

    default java.util.List<dev.voldechse.replayframework.api.playback.RecordedPlayer> recordedPlayers() {
        return java.util.List.of();
    }

    /**
     * Completes after all previously queued view packets have been processed
     * by the adapter and flushed to the viewer connection.
     *
     * @return completion stage for the current outbound queue
     */
    default CompletionStage<Void> awaitOutboundIdle() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Discards replay packets that were queued before a superseding seek.
     * Implementations must not discard packets already handed to the network.
     */
    default void discardQueuedReplayPackets() {
        // Bridges without an outbound queue have nothing to discard.
    }

    /**
     * Closes the bridge and removes bridge-owned handlers. Repeated calls are safe.
     */
    void close();
}
