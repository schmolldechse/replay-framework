package dev.voldechse.replayframework.format;

/**
 * Minecraft protocol phases that can occur in a replay segment.
 *
 * <p>Wire codes are explicit so changes to enum declaration order cannot
 * silently change the persisted format.</p>
 */
public enum PacketPhase {
    /** Client configuration packets required before the play view. */
    CONFIGURATION(1),
    /** Clientbound packets representing the replayed play state. */
    PLAY(2),
    /** Server-list/status protocol packets retained for complete capture. */
    STATUS(3),
    /** Login protocol packets retained for complete capture. */
    LOGIN(4),
    /** Handshake protocol packets retained for complete capture. */
    HANDSHAKING(5);

    private final int wireCode;

    PacketPhase(int wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * Returns the stable numeric representation used in segment records.
     *
     * @return stable wire code
     */
    public int wireCode() {
        return wireCode;
    }

    /**
     * Resolves a persisted phase code.
     *
     * @param wireCode persisted code
     * @return matching phase
     * @throws IllegalArgumentException when the code is unknown
     */
    public static PacketPhase fromWireCode(int wireCode) {
        for (PacketPhase phase : values()) {
            if (phase.wireCode == wireCode) {
                return phase;
            }
        }
        throw new IllegalArgumentException("Unknown packet phase wire code: " + wireCode);
    }
}
