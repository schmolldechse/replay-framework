package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.format.RawPacketFrame;

/**
 * Adapter boundary for writing validated replay packets to one prepared viewer.
 */
public interface PlaybackBridge {

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
     * Closes the bridge and removes bridge-owned handlers. Repeated calls are safe.
     */
    void close();
}
