package dev.voldechse.replayframework.adapter.paper.packet;

import java.util.Objects;
import java.util.Optional;

/** Semantic rewrite result used between a packet definition's read and rebuild hooks. */
public record ValueRewriteResult<V>(Decision decision, Optional<V> value, String reason) {

    public ValueRewriteResult {
        Objects.requireNonNull(decision, "decision");
        value = Objects.requireNonNull(value, "value");
        reason = Objects.requireNonNull(reason, "reason");
        if ((decision == Decision.SEND) != value.isPresent()) {
            throw new IllegalArgumentException("only SEND results contain a value");
        }
    }

    public static <V> ValueRewriteResult<V> send(V value) {
        return new ValueRewriteResult<>(Decision.SEND, Optional.of(value), "");
    }

    public static <V> ValueRewriteResult<V> drop(String reason) {
        return new ValueRewriteResult<>(Decision.DROP_LOCAL, Optional.empty(), reason);
    }

    public static <V> ValueRewriteResult<V> fail(String reason) {
        return new ValueRewriteResult<>(Decision.FAIL, Optional.empty(), reason);
    }

    public enum Decision {
        SEND,
        DROP_LOCAL,
        FAIL
    }
}
