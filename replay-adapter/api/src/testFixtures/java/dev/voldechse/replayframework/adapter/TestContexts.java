package dev.voldechse.replayframework.adapter;

import java.util.Optional;

/** Capture contexts for tests that do not look at a packet's meaning. */
public final class TestContexts {

    private TestContexts() {
    }

    /** A scene packet of a player connection with no known facts. */
    public static CaptureContext plain() {
        return new CaptureContext(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
