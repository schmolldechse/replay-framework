package dev.voldechse.replayframework.format;

import java.io.IOException;

/**
 * Indicates that a segment cannot be trusted as a complete replay artifact.
 */
public class CorruptReplaySegmentException extends IOException {

    public CorruptReplaySegmentException(String message) {
        super(message);
    }

    public CorruptReplaySegmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
