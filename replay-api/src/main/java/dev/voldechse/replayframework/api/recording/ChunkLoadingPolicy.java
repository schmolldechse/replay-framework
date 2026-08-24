package dev.voldechse.replayframework.api.recording;

/**
 * Controls whether a recording may load chunks before it starts.
 */
public enum ChunkLoadingPolicy {
    /** Capture only chunks that are already loaded or become loaded naturally. */
    LOADED_ONLY,
    /** Preload only the explicitly bounded regions in the recording scope. */
    PRELOAD_SCOPE
}
