package dev.voldechse.replayframework.adapter.paper.packet;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Objects;

/**
 * One explicit packet implementation. The definition owns descriptor policy
 * and the decoded rewrite hook; the catalog owns lookup and uniqueness.
 *
 * @param <C> playback-session context type
 */
public abstract class PacketDefinition<C> {

    private static final String TYPE_PREFIX = "play.clientbound.";

    private final int packetId;
    private final String typeName;
    private final PacketClassification classification;

    protected PacketDefinition(
            int packetId,
            String identifier,
            PacketClassification classification) {
        if (packetId < 0) {
            throw new IllegalArgumentException("packetId must not be negative");
        }
        Objects.requireNonNull(identifier, "identifier");
        if (!identifier.startsWith("minecraft:") || identifier.length() == "minecraft:".length()) {
            throw new IllegalArgumentException("identifier must be a Minecraft packet identifier");
        }
        this.packetId = packetId;
        this.typeName = TYPE_PREFIX + identifier;
        this.classification = Objects.requireNonNull(classification, "classification");
    }

    /** Returns the expected numeric ID in this version's PLAY clientbound registry. */
    public final int packetId() {
        return packetId;
    }

    public final String typeName() {
        return typeName;
    }

    public final PacketClassification classification() {
        return classification;
    }

    /** Applies policy, decodes only when permitted, then reads/rewrites/rebuilds the packet. */
    public final PacketRewriteResult handlePlayback(
            RawPacketFrame frame,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec,
            C context) {
        Objects.requireNonNull(frame, "frame");
        validateDescriptor(descriptor);
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(context, "context");
        if (frame.phase() != descriptor.phase() || frame.packetId() != descriptor.packetId()) {
            throw new IncompatibleAdapterException(
                    "replay frame does not match descriptor for " + typeName);
        }
        return switch (classification.playbackScope()) {
            case VIEWER_LOCAL -> PacketRewriteResult.drop(
                    "recorded connection state does not belong to the observer");
            case UNSAFE -> PacketRewriteResult.fail(
                    "unsafe replay packet reached the rewrite boundary");
            case SAFE, REWRITE_IDENTITIES -> rewriteDecoded(
                    Objects.requireNonNull(decode(frame, codec), "decode returned null"), context);
        };
    }

    /** Encodes and validates the rebuilt packet against the same descriptor. */
    public final PacketCodec.EncodedPacket encode(
            Object rebuiltPacket,
            PacketDescriptor descriptor,
            PacketCodec<Object> codec) {
        Objects.requireNonNull(rebuiltPacket, "rebuiltPacket");
        validateDescriptor(descriptor);
        Objects.requireNonNull(codec, "codec");
        PacketCodec.EncodedPacket encoded = Objects.requireNonNull(
                encode(rebuiltPacket, codec), "encode returned null");
        if (encoded.phase() != PacketPhase.PLAY
                || encoded.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || encoded.packetId() != descriptor.packetId()) {
            throw new IncompatibleAdapterException(
                    "rebuilt packet encoded as a different descriptor for " + typeName);
        }
        return encoded;
    }

    /**
     * Applies descriptor policy before invoking a packet-specific rewrite.
     * Viewer-local and unsafe packets are rejected without inspecting payload
     * fields beyond the already decoded object supplied by the bridge.
     */
    public final PacketRewriteResult rewrite(
            Object decodedPacket,
            PacketDescriptor descriptor,
            C context) {
        Objects.requireNonNull(decodedPacket, "decodedPacket");
        validateDescriptor(descriptor);
        Objects.requireNonNull(context, "context");
        return switch (classification.playbackScope()) {
            case VIEWER_LOCAL -> PacketRewriteResult.drop(
                    "recorded connection state does not belong to the observer");
            case UNSAFE -> PacketRewriteResult.fail(
                    "unsafe replay packet reached the rewrite boundary");
            case SAFE -> rewriteSafe(decodedPacket, context);
            case REWRITE_IDENTITIES -> rewriteIdentities(decodedPacket, context);
        };
    }

    protected Object decode(RawPacketFrame frame, PacketCodec<Object> codec) {
        return codec.decode(frame);
    }

    protected PacketCodec.EncodedPacket encode(Object packet, PacketCodec<Object> codec) {
        return codec.encode(packet);
    }

    private PacketRewriteResult rewriteDecoded(Object decodedPacket, C context) {
        return switch (classification.playbackScope()) {
            case SAFE -> rewriteSafe(decodedPacket, context);
            case REWRITE_IDENTITIES -> rewriteIdentities(decodedPacket, context);
            case VIEWER_LOCAL, UNSAFE -> throw new IllegalStateException(
                    "policy packets must be handled before decode");
        };
    }

    private void validateDescriptor(PacketDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!typeName.equals(descriptor.typeName())) {
            throw new IncompatibleAdapterException(
                    "packet definition " + typeName
                            + " does not match descriptor " + descriptor.typeName());
        }
        if (packetId != descriptor.packetId()) {
            throw new IncompatibleAdapterException(
                    "packet definition " + typeName + " expects ID " + packetId
                            + " but descriptor uses " + descriptor.packetId());
        }
        if (descriptor.phase() != PacketPhase.PLAY
                || descriptor.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || !descriptor.replayable()) {
            throw new IncompatibleAdapterException(
                    "packet descriptor is not permitted for playback: " + typeName);
        }
        if (!classification.playbackScope().equals(descriptor.playbackScope())) {
            throw new IncompatibleAdapterException(
                    "packet definition scope differs from descriptor for " + typeName);
        }
    }

    /** Allows payload-sensitive SAFE implementations to sanitize or drop. */
    protected PacketRewriteResult rewriteSafe(Object decodedPacket, C context) {
        return PacketRewriteResult.send(decodedPacket);
    }

    /** Identity-bearing implementations must rebuild all verified references. */
    protected PacketRewriteResult rewriteIdentities(Object decodedPacket, C context) {
        throw new IncompatibleAdapterException(
                "no identity rewrite is implemented for " + typeName);
    }
}
