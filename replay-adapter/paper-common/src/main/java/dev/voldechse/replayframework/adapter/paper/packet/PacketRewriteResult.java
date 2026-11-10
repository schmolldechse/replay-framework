package dev.voldechse.replayframework.adapter.paper.packet;

import java.util.Objects;
import java.util.Optional;

/** Result of one decoded packet implementation at the neutral-observer boundary. */
public record PacketRewriteResult(
        Decision decision,
        Optional<Object> packet,
        String reason) {

    public PacketRewriteResult {
        Objects.requireNonNull(decision, "decision");
        packet = Objects.requireNonNull(packet, "packet");
        reason = Objects.requireNonNull(reason, "reason");
        if ((decision == Decision.SEND) != packet.isPresent()) {
            throw new IllegalArgumentException("only SEND may carry a packet");
        }
    }

    public static PacketRewriteResult send(Object packet) {
        return new PacketRewriteResult(
                Decision.SEND,
                Optional.of(Objects.requireNonNull(packet, "packet")),
                "send");
    }

    public static PacketRewriteResult drop(String reason) {
        return new PacketRewriteResult(Decision.DROP_LOCAL, Optional.empty(), reason);
    }

    public static PacketRewriteResult fail(String reason) {
        return new PacketRewriteResult(Decision.FAIL, Optional.empty(), reason);
    }

    public enum Decision {
        SEND,
        DROP_LOCAL,
        FAIL
    }
}
