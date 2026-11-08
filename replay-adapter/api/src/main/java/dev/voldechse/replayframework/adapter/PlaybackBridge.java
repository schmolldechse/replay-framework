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
