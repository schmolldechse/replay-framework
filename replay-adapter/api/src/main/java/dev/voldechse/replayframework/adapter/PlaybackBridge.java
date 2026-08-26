package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.RawPacketFrame;
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
     * Sends one validated clientbound replay frame.
     *
     * @param frame raw frame from the matching adapter format
     * @throws IncompatibleAdapterException when the frame is unknown or blocked
     * @throws IllegalStateException after {@link #close()}
     */
    void send(RawPacketFrame frame);

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
