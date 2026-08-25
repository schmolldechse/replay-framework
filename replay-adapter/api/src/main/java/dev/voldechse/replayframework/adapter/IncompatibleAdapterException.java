package dev.voldechse.replayframework.adapter;

/**
 * Signals that a replay, packet frame or adapter registry is incompatible
 * with the active adapter.
 *
 * <p>Messages must contain stable compatibility identifiers only. Packet
 * payloads, credentials and player state must never be included.</p>
 */
public class IncompatibleAdapterException extends RuntimeException {

    /**
     * Creates an incompatibility failure.
     *
     * @param message safe diagnostic message without packet payloads
     */
    public IncompatibleAdapterException(String message) {
        super(message);
    }

    /**
     * Creates an incompatibility failure with its cause.
     *
     * @param message safe diagnostic message without packet payloads
     * @param cause underlying compatibility failure
     */
    public IncompatibleAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
