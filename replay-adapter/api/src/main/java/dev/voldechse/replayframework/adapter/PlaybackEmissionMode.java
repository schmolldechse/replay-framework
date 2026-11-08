package dev.voldechse.replayframework.adapter;

/** Describes why replay frames are being emitted to an observer. */
public enum PlaybackEmissionMode {
    /** Ordered timeline playback, including allowed transient presentation. */
    LIVE,
    /** State reconstruction, limited by adapters to durable replay state. */
    RECONSTRUCTION
}
