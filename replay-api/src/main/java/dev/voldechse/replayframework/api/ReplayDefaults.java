package dev.voldechse.replayframework.api;

import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.api.recording.RecordingOptions;
import dev.voldechse.replayframework.api.recording.ReplayBudget;
import java.util.Objects;

/** Immutable runtime defaults supplied by the installed Replay Framework. */
public record ReplayDefaults(
        ReplayBudget recordingBudget,
        RecordingOptions recordingOptions,
        PlaybackBufferOptions playbackBufferOptions) {

    /** Validates the immutable request defaults exposed to integrations. */
    public ReplayDefaults {
        Objects.requireNonNull(recordingBudget, "recordingBudget");
        Objects.requireNonNull(recordingOptions, "recordingOptions");
        Objects.requireNonNull(playbackBufferOptions, "playbackBufferOptions");
    }
}
