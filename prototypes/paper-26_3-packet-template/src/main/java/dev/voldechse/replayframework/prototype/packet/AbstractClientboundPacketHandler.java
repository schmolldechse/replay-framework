package dev.voldechse.replayframework.prototype.packet;

import java.util.Objects;

/**
 * Shared template method for one Paper-version-specific clientbound packet.
 * Concrete handlers own semantics; this class owns invariant validation and
 * the decode/read/rewrite/rebuild/encode order.
 */
public abstract class AbstractClientboundPacketHandler<P, V> {

    private final PacketSpec spec;
    private final Class<P> nativeType;

    protected AbstractClientboundPacketHandler(PacketSpec spec, Class<P> nativeType) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.nativeType = Objects.requireNonNull(nativeType, "nativeType");
    }

    public final PacketSpec spec() {
        return spec;
    }

    public final PlaybackResult handle(
            RawPacketFrame frame,
            PacketCodecSession codec,
            PlaybackContext context) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(context, "context");
        if (frame.packetId() != spec.packetId()) {
            throw new IllegalArgumentException(
                    "frame ID does not match " + spec.typeName());
        }

        // Connection-local and blocked packets never reach the native decoder.
        if (spec.playbackPolicy() == PacketSpec.PlaybackPolicy.DROP_VIEWER_LOCAL) {
            return PlaybackResult.drop("recorded connection state is viewer-local");
        }
        if (spec.playbackPolicy() == PacketSpec.PlaybackPolicy.BLOCK) {
            return PlaybackResult.fail("packet has no safe PLAY playback contract");
        }

        Object decoded = Objects.requireNonNull(codec.decode(frame), "codec.decode result");
        if (!nativeType.isInstance(decoded)) {
            throw new IllegalStateException(
                    "decoded type for " + spec.typeName() + " was "
                            + decoded.getClass().getName() + ", expected " + nativeType.getName());
        }

        V value = Objects.requireNonNull(
                read(nativeType.cast(decoded)),
                "packet handler read result");
        RewriteDecision<V> decision = Objects.requireNonNull(
                rewrite(value, context),
                "packet handler rewrite result");
        if (decision.kind() == RewriteDecision.Kind.DROP) {
            return PlaybackResult.drop(decision.reason());
        }
        if (decision.kind() == RewriteDecision.Kind.FAIL) {
            return PlaybackResult.fail(decision.reason());
        }

        V rewritten = decision.value().orElseThrow();
        P rebuilt = Objects.requireNonNull(
                rebuild(rewritten),
                "packet handler rebuild result");
        EncodedPacket encoded = Objects.requireNonNull(
                codec.encode(rebuilt),
                "codec.encode result");
        if (encoded.packetId() != spec.packetId()) {
            throw new IllegalStateException(
                    "rebuilt " + spec.typeName() + " encoded as packet ID "
                            + encoded.packetId() + " instead of " + spec.packetId());
        }
        return PlaybackResult.send(encoded);
    }

    /** Extracts the packet fields relevant to this handler into immutable data. */
    protected abstract V read(P packet);

    /** Applies playback-session mappings without mutating the decoded packet. */
    protected abstract RewriteDecision<V> rewrite(V value, PlaybackContext context);

    /** Builds a new native packet from the rewritten immutable data. */
    protected abstract P rebuild(V value);
}
