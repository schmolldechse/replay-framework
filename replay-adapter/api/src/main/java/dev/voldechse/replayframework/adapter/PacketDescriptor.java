package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.format.PacketPhase;
import java.util.Objects;

/**
 * Immutable, adapter-owned description of one protocol packet type.
 *
 * @param typeName stable adapter packet type name
 * @param phase protocol phase in which the packet is valid
 * @param direction protocol direction
 * @param packetId numeric packet identifier within the phase and direction
 * @param disposition semantic capture and playback classification
 * @param captureByDefault structural capture eligibility before user policy
 * @param replayable structural playback eligibility
 * @param checkpointRelevant whether checkpoint generation considers the packet
 * @param codecKey stable identifier for the version-bound packet codec
 */
public record PacketDescriptor(
        String typeName,
        PacketPhase phase,
        Direction direction,
        int packetId,
        PacketDisposition disposition,
        boolean captureByDefault,
        boolean replayable,
        boolean checkpointRelevant,
        String codecKey) {

    /** Validates packet identity, direction and disposition invariants. */
    public PacketDescriptor {
        typeName = requireStableText(typeName, "typeName");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(direction, "direction");
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        Objects.requireNonNull(disposition, "disposition");
        codecKey = requireStableText(codecKey, "codecKey");

        if (disposition == PacketDisposition.CONTROL
                || disposition == PacketDisposition.UNSUPPORTED) {
            requireDisabled(disposition, captureByDefault, replayable, checkpointRelevant);
        }
        if (direction == Direction.SERVERBOUND) {
            requireDisabled(disposition, captureByDefault, replayable, checkpointRelevant);
        }
    }

    private static void requireDisabled(
            PacketDisposition disposition,
            boolean captureByDefault,
            boolean replayable,
            boolean checkpointRelevant) {
        if (captureByDefault || replayable || checkpointRelevant) {
            throw new IllegalArgumentException(
                    disposition + " packets cannot be captured, replayed or checkpoint-relevant");
        }
    }

    private static String requireStableText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain whitespace or control characters");
            }
        }
        return value;
    }

    /** Protocol directions with explicit, stable fingerprint wire codes. */
    public enum Direction {
        /** Packet sent from server to client. */
        CLIENTBOUND(1),
        /** Packet sent from client to server; never replayed by this framework. */
        SERVERBOUND(2);

        private final int wireCode;

        Direction(int wireCode) {
            this.wireCode = wireCode;
        }

        /**
         * Returns the stable numeric value used for registry fingerprints.
         *
         * @return stable direction wire code
         */
        public int wireCode() {
            return wireCode;
        }
    }
}
