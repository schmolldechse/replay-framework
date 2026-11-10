package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;

/** Immutable policy and live-registry binding for one PLAY clientbound packet. */
public record PacketSpec(
        String typeName,
        int packetId,
        PlaybackPolicy playbackPolicy,
        boolean checkpointRelevant) {

    public PacketSpec {
        Objects.requireNonNull(typeName, "typeName");
        if (!typeName.startsWith("play.clientbound.minecraft:")) {
            throw new IllegalArgumentException("typeName must be a PLAY clientbound identifier");
        }
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        Objects.requireNonNull(playbackPolicy, "playbackPolicy");
        if (playbackPolicy == PlaybackPolicy.BLOCK && checkpointRelevant) {
            throw new IllegalArgumentException("blocked packets cannot be checkpoint-relevant");
        }
    }

    public enum PlaybackPolicy {
        PASS_THROUGH,
        REWRITE_IDENTITIES,
        DROP_VIEWER_LOCAL,
        BLOCK
    }
}
