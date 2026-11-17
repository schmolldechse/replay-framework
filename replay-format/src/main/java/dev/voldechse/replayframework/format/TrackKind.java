package dev.voldechse.replayframework.format;

/**
 * The kinds of tracks a replay is made of.
 *
 * <p>Wire codes are explicit so the declaration order cannot change the
 * persisted format.</p>
 */
public enum TrackKind {
    /** World-wide scene packets such as chat and the tab list. */
    GLOBAL(1),
    /** Scene packets of one world. */
    WORLD(2),
    /** The state of one subject that the scene does not show. */
    STATE(3);

    private final int wireCode;

    TrackKind(int wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * Returns the stable numeric representation.
     *
     * @return wire code
     */
    public int wireCode() {
        return wireCode;
    }

    /**
     * Resolves a wire code.
     *
     * @param wireCode stable numeric representation
     * @return track kind
     * @throws IllegalArgumentException for an unknown code
     */
    public static TrackKind fromWireCode(int wireCode) {
        for (TrackKind kind : values()) {
            if (kind.wireCode == wireCode) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown track kind: " + wireCode);
    }
}
