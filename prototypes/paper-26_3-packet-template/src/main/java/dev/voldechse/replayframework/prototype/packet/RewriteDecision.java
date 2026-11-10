package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;
import java.util.Optional;

/** Packet-specific semantic decision before a native packet is rebuilt. */
public record RewriteDecision<V>(
        Kind kind,
        Optional<V> value,
        String reason) {

    public RewriteDecision {
        Objects.requireNonNull(kind, "kind");
        value = Objects.requireNonNull(value, "value");
        reason = Objects.requireNonNull(reason, "reason");
        if ((kind == Kind.SEND) != value.isPresent()) {
            throw new IllegalArgumentException("only SEND may carry a rewritten value");
        }
    }

    public static <V> RewriteDecision<V> send(V value) {
        return new RewriteDecision<>(Kind.SEND, Optional.of(value), "send");
    }

    public static <V> RewriteDecision<V> drop(String reason) {
        return new RewriteDecision<>(Kind.DROP, Optional.empty(), reason);
    }

    public static <V> RewriteDecision<V> fail(String reason) {
        return new RewriteDecision<>(Kind.FAIL, Optional.empty(), reason);
    }

    public enum Kind {
        SEND,
        DROP,
        FAIL
    }
}
