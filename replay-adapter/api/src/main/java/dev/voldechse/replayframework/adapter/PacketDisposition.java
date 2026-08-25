package dev.voldechse.replayframework.adapter;

/**
 * Runtime classification of a protocol packet for capture and playback.
 */
public enum PacketDisposition {
    /** Long-lived client-visible state used to reconstruct a checkpoint. */
    STATEFUL,
    /** Short-lived effects such as sounds, particles and animations. */
    EPHEMERAL,
    /** User-configurable presentation data such as chat, titles and custom payloads. */
    CONFIGURABLE,
    /** Connection-control data that must never become replay data. */
    CONTROL,
    /** Deliberately unsupported data that must not be guessed or replayed. */
    UNSUPPORTED
}
