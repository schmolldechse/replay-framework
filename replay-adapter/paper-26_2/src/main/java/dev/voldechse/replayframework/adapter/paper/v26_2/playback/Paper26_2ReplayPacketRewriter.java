package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.playback.ReplayIdentityMap;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Version-local dispatch boundary that routes recorded frames through the
 * packet catalog. It never decodes or encodes blocked control packets.
 */
public final class Paper26_2ReplayPacketRewriter {

    private final ReplayIdentityMap identities;

    public Paper26_2ReplayPacketRewriter(
            PlaybackIdentityContext context,
            Set<UUID> reservedUuids,
            Set<Integer> reservedEntityIds) {
        this.identities = new ReplayIdentityMap(
                Objects.requireNonNull(context, "context"),
                Objects.requireNonNull(reservedUuids, "reservedUuids"),
                Objects.requireNonNull(reservedEntityIds, "reservedEntityIds"));
    }

    RewriteResult rewrite(
            RawPacketFrame frame,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec) {
        PacketRewriteResult result = Paper26_2PacketCatalog.catalog()
                .require(Objects.requireNonNull(descriptor, "descriptor").typeName())
                .handlePlayback(
                        Objects.requireNonNull(frame, "frame"),
                        descriptor,
                        Objects.requireNonNull(codec, "codec"),
                        this);
        return toRewriteResult(result);
    }

    PacketCodec.EncodedPacket encode(
            Object rebuiltPacket,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec) {
        return Paper26_2PacketCatalog.catalog()
                .require(Objects.requireNonNull(descriptor, "descriptor").typeName())
                .encode(
                        Objects.requireNonNull(rebuiltPacket, "rebuiltPacket"),
                        descriptor,
                        Objects.requireNonNull(codec, "codec"));
    }

    /** Maps a recorded UUID into this playback session's isolated identity space. */
    public UUID mapUuid(UUID recordedId) {
        return identities.mapUuid(recordedId);
    }

    /** Maps a recorded entity ID into this playback session's isolated ID space. */
    public int mapEntityId(int recordedId) {
        return identities.mapEntityId(recordedId);
    }

    void clear() {
        identities.clear();
    }

    void resetView() {}

    private static RewriteResult toRewriteResult(PacketRewriteResult result) {
        return switch (result.decision()) {
            case SEND -> RewriteResult.send(
                    result.packet().orElseThrow(), result.outputPacketId());
            case DROP_LOCAL -> RewriteResult.dropLocal(result.reason());
            case FAIL -> RewriteResult.fail(result.reason());
        };
    }

    /** Result of the version-bound packet dispatch step. */
    record RewriteResult(
            Decision decision,
            Object packet,
            Optional<Integer> outputPacketId,
            String reason) {
        RewriteResult {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(outputPacketId, "outputPacketId");
            if (decision == Decision.SEND) {
                Objects.requireNonNull(packet, "packet");
            } else if (packet != null) {
                throw new IllegalArgumentException("non-send rewrite result must not carry a packet");
            }
            if (decision != Decision.SEND && outputPacketId.isPresent()) {
                throw new IllegalArgumentException("non-send rewrite result must not declare output ID");
            }
            Objects.requireNonNull(reason, "reason");
        }

        static RewriteResult fail(String reason) {
            return new RewriteResult(Decision.FAIL, null, Optional.empty(), reason);
        }

        static RewriteResult send(Object packet, Optional<Integer> outputPacketId) {
            return new RewriteResult(Decision.SEND, packet, outputPacketId, "send");
        }

        static RewriteResult dropLocal(String reason) {
            return new RewriteResult(Decision.DROP_LOCAL, null, Optional.empty(), reason);
        }

        enum Decision {
            SEND,
            DROP_LOCAL,
            FAIL
        }
    }
}
