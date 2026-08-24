package dev.voldechse.replayframework.api.recording;

/**
 * Reason why a recording was cleanly finalized as an available replay.
 */
public enum ReplayCompletionReason {
    /** The recording was stopped explicitly. */
    MANUAL,
    /** The server shutdown sequence finalized the recording. */
    SERVER_SHUTDOWN,
    /** The configured duration budget was reached. */
    DURATION_LIMIT,
    /** The configured total-byte budget was reached. */
    BYTE_LIMIT,
    /** The configured packet-count budget was reached. */
    PACKET_LIMIT,
    /** The configured segment-count budget was reached. */
    SEGMENT_LIMIT
}
