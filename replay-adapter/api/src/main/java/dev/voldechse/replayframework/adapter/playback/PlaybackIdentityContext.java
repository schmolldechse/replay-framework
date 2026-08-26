package dev.voldechse.replayframework.adapter.playback;

import java.util.Objects;
import java.util.UUID;

/** Immutable identity boundary for one isolated replay-to-viewer session. */
public record PlaybackIdentityContext(
        UUID observerId,
        UUID playbackSessionId) {

    public PlaybackIdentityContext {
        Objects.requireNonNull(observerId, "observerId");
        Objects.requireNonNull(playbackSessionId, "playbackSessionId");
    }
}
