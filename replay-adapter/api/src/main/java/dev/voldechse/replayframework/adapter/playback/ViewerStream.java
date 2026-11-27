package dev.voldechse.replayframework.adapter.playback;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PlaybackEmissionMode;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Writes replay frames to one viewer and keeps the viewer's client apart from the live world. */
public interface ViewerStream {

    /** Activates live replay-packet suppression after observer placement. */
    void activateReplayIsolation();

    /**
     * Sends one validated clientbound replay frame during playback.
     *
     * @param frame raw frame from the matching adapter format
     * @throws IncompatibleAdapterException when the frame is unknown or blocked
     * @throws IllegalStateException after the bridge was closed
     */
    void send(RawPacketFrame frame);

    /**
     * Enqueues an ordered batch for one emission purpose. During
     * reconstruction only packets that restore state are sent.
     *
     * @param frames ordered frames from the matching adapter format
     * @param mode live playback or state reconstruction
     */
    void sendBatch(List<RawPacketFrame> frames, PlaybackEmissionMode mode);

    /**
     * Clears the current replay view using adapter-valid clientbound packets.
     *
     * @throws IllegalStateException after the bridge was closed
     */
    void resetView();

    /**
     * Completes after all previously queued view packets have been processed
     * and flushed to the viewer connection.
     */
    CompletionStage<Void> awaitOutboundIdle();

    /**
     * Discards replay packets that were queued before a superseding seek.
     * Packets already handed to the network are not discarded.
     */
    void discardQueuedReplayPackets();
}
