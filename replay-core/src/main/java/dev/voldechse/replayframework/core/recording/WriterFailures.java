package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.api.recording.ReplayFailureCode;
import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The failures of a recording's writer and the code each one is reported
 * with. The appender, its tracks and its checkpoint barriers throw them;
 * the appender classifies them once, when the writer stops.
 */
final class WriterFailures {

    private WriterFailures() {
    }

    /** The failure code of a writer failure. */
    static ReplayFailureCode classify(Throwable failure) {
        if (failure instanceof StorageWriteException || failure instanceof IOException) {
            return ReplayFailureCode.STORAGE_ERROR;
        }
        if (failure instanceof AdapterCheckpointException
                || failure instanceof IllegalArgumentException) {
            return ReplayFailureCode.ADAPTER_ERROR;
        }
        if (failure instanceof CorruptWriterException) {
            return ReplayFailureCode.CORRUPT_DATA;
        }
        return ReplayFailureCode.INTERNAL_ERROR;
    }

    /** The cause behind completion and execution wrappers. */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** A replay file could not be written. */
    static final class StorageWriteException extends RuntimeException {
        StorageWriteException(IOException cause) {
            super("replay artifact write failed", cause);
        }
    }

    /** What would be written breaks a rule of the replay format. */
    static final class CorruptWriterException extends RuntimeException {
        CorruptWriterException(Throwable cause) {
            super("replay artifact format invariant failed", cause);
        }
    }

    /** The adapter could not capture a checkpoint. */
    static final class AdapterCheckpointException extends RuntimeException {
        AdapterCheckpointException(Throwable cause) {
            super("checkpoint encoder failed", cause);
        }
    }
}
