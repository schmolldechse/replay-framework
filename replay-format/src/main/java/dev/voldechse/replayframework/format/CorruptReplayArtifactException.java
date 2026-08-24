package dev.voldechse.replayframework.format;

import java.io.IOException;

/**
 * Indicates that a checkpoint, index, or manifest cannot be trusted as a
 * complete replay artifact.
 */
public class CorruptReplayArtifactException extends IOException {

    /**
     * Creates a format error with a descriptive message.
     *
     * @param message description of the invalid artifact
     */
    public CorruptReplayArtifactException(String message) {
        super(message);
    }

    /**
     * Creates a format error with its decoding cause.
     *
     * @param message description of the invalid artifact
     * @param cause underlying decoding or validation failure
     */
    public CorruptReplayArtifactException(String message, Throwable cause) {
        super(message, cause);
    }
}
