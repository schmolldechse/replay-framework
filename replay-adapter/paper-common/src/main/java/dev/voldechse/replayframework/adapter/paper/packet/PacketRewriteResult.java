package dev.voldechse.replayframework.adapter.paper.packet;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of one decoded packet implementation at the neutral-observer boundary.
 *
 * <p>{@code outputPacketId} is empty when the rebuilt packet uses the source
 * descriptor. Definitions that transform one native packet into another
 * registered packet type declare that target ID so the adapter can validate
 * and encode it through the target definition.</p>
 */
public record PacketRewriteResult(
        Decision decision,
        Optional<Object> packet,
        Optional<Integer> outputPacketId,
        String reason) {

    /** Keeps the original same-packet emission contract source-compatible. */
    public PacketRewriteResult(Decision decision, Optional<Object> packet, String reason) {
        this(decision, packet, Optional.empty(), reason);
    }

    public PacketRewriteResult {
        Objects.requireNonNull(decision, "decision");
        packet = Objects.requireNonNull(packet, "packet");
        outputPacketId = Objects.requireNonNull(outputPacketId, "outputPacketId");
        reason = Objects.requireNonNull(reason, "reason");
        if ((decision == Decision.SEND) != packet.isPresent()) {
            throw new IllegalArgumentException("only SEND may carry a packet");
        }
        if (outputPacketId.isPresent() && decision != Decision.SEND) {
            throw new IllegalArgumentException("only SEND may declare an output packet ID");
        }
        if (outputPacketId.filter(packetId -> packetId < 0).isPresent()) {
            throw new IllegalArgumentException("output packet ID must not be negative");
        }
    }

    public static PacketRewriteResult send(Object packet) {
        return new PacketRewriteResult(
                Decision.SEND,
                Optional.of(Objects.requireNonNull(packet, "packet")),
                Optional.empty(),
                "send");
    }

    /** Sends a rebuilt packet using another registered clientbound packet ID. */
    public static PacketRewriteResult sendAs(Object packet, int outputPacketId) {
        if (outputPacketId < 0) {
            throw new IllegalArgumentException("outputPacketId must not be negative");
        }
        return new PacketRewriteResult(
                Decision.SEND,
                Optional.of(Objects.requireNonNull(packet, "packet")),
                Optional.of(outputPacketId),
                "send");
    }

    public static PacketRewriteResult drop(String reason) {
        return new PacketRewriteResult(
                Decision.DROP_LOCAL, Optional.empty(), Optional.empty(), reason);
    }

    public static PacketRewriteResult fail(String reason) {
        return new PacketRewriteResult(Decision.FAIL, Optional.empty(), Optional.empty(), reason);
    }

    public enum Decision {
        SEND,
        DROP_LOCAL,
        FAIL
    }
}
