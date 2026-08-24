package dev.voldechse.replayframework.api.recording;

/**
 * Stable failure classification for an unplayable recording or replay
 * operation.
 */
public enum ReplayFailureCode {
    /** The server process stopped before the recording could be finalized. */
    SERVER_CRASH,
    /** The bounded capture or persistence queue could not accept a packet. */
    QUEUE_OVERFLOW,
    /** The adapter rejected or could not process a packet or state. */
    ADAPTER_ERROR,
    /** The configured artifact storage failed. */
    STORAGE_ERROR,
    /** The replay catalog database operation failed. */
    DATABASE_ERROR,
    /** A replay artifact failed an integrity or decoding check. */
    CORRUPT_DATA,
    /** The active adapter does not match the replay adapter contract. */
    INCOMPATIBLE_ADAPTER,
    /** An otherwise unclassified internal failure occurred. */
    INTERNAL_ERROR
}
