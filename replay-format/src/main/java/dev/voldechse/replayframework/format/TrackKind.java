package dev.voldechse.replayframework.format;

/**
 * The kinds of tracks a replay is made of.
 *
 * <p>Each kind says what identifies one of its tracks and what playback does
 * with it. Descriptor validation, the uniqueness of tracks in a manifest and
 * the selection of tracks in playback follow from these properties, so a new
 * kind is one constant here plus its route in recording and its consumer in
 * playback.</p>
 *
 * <p>Wire codes are explicit so the declaration order cannot change the
 * persisted format.</p>
 */
public enum TrackKind {
    /** World-wide scene packets such as chat and the tab list. */
    GLOBAL(1, KeyedBy.NOTHING, Role.SCENE),
    /** Scene packets of one world. */
    WORLD(2, KeyedBy.WORLD, Role.SCENE),
    /** The state of one subject that the scene does not show. */
    STATE(3, KeyedBy.SUBJECT, Role.STATE),
    /**
     * The state of the blocks of one world that the scene does not show, such
     * as the contents of chests. Each frame names its block.
     */
    BLOCK_STATE(4, KeyedBy.WORLD, Role.STATE);

    private final int wireCode;
    private final KeyedBy keyedBy;
    private final Role role;

    TrackKind(int wireCode, KeyedBy keyedBy, Role role) {
        this.wireCode = wireCode;
        this.keyedBy = keyedBy;
        this.role = role;
    }

    /** What identifies one track of this kind within a replay. */
    public KeyedBy keyedBy() {
        return keyedBy;
    }

    /** What playback does with tracks of this kind. */
    public Role role() {
        return role;
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

    /**
     * What identifies a track of a kind. A replay has at most one track per
     * kind and key value, so a kind keyed by nothing has at most one track.
     */
    public enum KeyedBy {
        /** Nothing: the kind has one track. */
        NOTHING,
        /** A world. */
        WORLD,
        /** A subject. */
        SUBJECT
    }

    /** What playback does with a track. */
    public enum Role {
        /** Played to the viewer as the scene. */
        SCENE,
        /** Read only when the state of a subject or block is asked for. */
        STATE
    }
}
