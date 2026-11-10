package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;
import java.util.Optional;

/** Result produced after policy, rewrite, rebuild and encode. */
public record PlaybackResult(
        Decision decision,
        Optional<EncodedPacket> packet,
        String reason) {

    public PlaybackResult {
        Objects.requireNonNull(decision, "decision");
        packet = Objects.requireNonNull(packet, "packet");
        reason = Objects.requireNonNull(reason, "reason");
        if ((decision == Decision.SEND) != packet.isPresent()) {
            throw new IllegalArgumentException("only SEND may carry an encoded packet");
        }
    }

    public static PlaybackResult send(EncodedPacket packet) {
        return new PlaybackResult(Decision.SEND, Optional.of(packet), "send");
    }

    public static PlaybackResult drop(String reason) {
        return new PlaybackResult(Decision.DROP, Optional.empty(), reason);
    }

    public static PlaybackResult fail(String reason) {
        return new PlaybackResult(Decision.FAIL, Optional.empty(), reason);
    }

    public enum Decision {
        SEND,
        DROP,
        FAIL
    }
}
