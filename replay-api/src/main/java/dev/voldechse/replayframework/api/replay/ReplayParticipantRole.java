package dev.voldechse.replayframework.api.replay;

/**
 * Role of a participant recorded in a replay.
 */
public enum ReplayParticipantRole {
    /** A participant explicitly or implicitly marked as primary. */
    PRIMARY,
    /** A participant or entity observed within the recording scope. */
    OBSERVED
}
