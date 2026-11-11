package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Version-local dispatch boundary retained while the packet catalog is empty.
 * It never decodes or encodes a packet without an explicit implementation.
 */
public final class Paper26ReplayPacketRewriter {

    Paper26ReplayPacketRewriter(
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(reservedUuids, "reservedUuids");
        Objects.requireNonNull(reservedEntityIds, "reservedEntityIds");
    }

    RewriteResult rewrite(
            RawPacketFrame frame,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(codec, "codec");
        String typeName = Objects.requireNonNull(descriptor, "descriptor").typeName();
        return RewriteResult.fail("no Paper 26.2 packet implementation is registered for " + typeName);
    }

    PacketCodec.EncodedPacket encode(
            Object rebuiltPacket,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec) {
        throw new IncompatibleAdapterException(
                "Paper 26.2 packet encoding requires an explicit packet implementation");
    }

    void clear() {}

    void resetView() {}

    /** Result of the version-bound packet dispatch step. */
    record RewriteResult(Decision decision, Object packet, String reason) {
        RewriteResult {
            Objects.requireNonNull(decision, "decision");
            if (decision == Decision.SEND) {
                Objects.requireNonNull(packet, "packet");
            } else if (packet != null) {
                throw new IllegalArgumentException("non-send rewrite result must not carry a packet");
            }
            Objects.requireNonNull(reason, "reason");
        }

        static RewriteResult fail(String reason) {
            return new RewriteResult(Decision.FAIL, null, reason);
        }

        enum Decision {
            SEND,
            DROP_LOCAL,
            FAIL
        }
    }
}
