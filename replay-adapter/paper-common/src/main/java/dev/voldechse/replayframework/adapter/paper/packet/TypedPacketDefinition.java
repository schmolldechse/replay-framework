package dev.voldechse.replayframework.adapter.paper.packet;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.Objects;
import java.util.function.BiFunction;

/** Packet definition whose payload logic is bound to one verified native class. */
public final class TypedPacketDefinition<C, P> extends PacketDefinition<C> {

    private final Class<P> nativeType;
    private final BiFunction<P, C, PacketRewriteResult> rewriter;

    public TypedPacketDefinition(
            String identifier,
            PacketClassification classification,
            Class<P> nativeType,
            BiFunction<P, C, PacketRewriteResult> rewriter) {
        super(identifier, classification);
        this.nativeType = Objects.requireNonNull(nativeType, "nativeType");
        this.rewriter = Objects.requireNonNull(rewriter, "rewriter");
        var scope = classification.playbackScope();
        if (scope != dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.SAFE
                && scope != dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES) {
            throw new IllegalArgumentException(
                    "typed packet definitions must be safe or identity-bearing");
        }
    }

    @Override
    protected PacketRewriteResult rewriteSafe(Object decodedPacket, C context) {
        return apply(decodedPacket, context);
    }

    @Override
    protected PacketRewriteResult rewriteIdentities(Object decodedPacket, C context) {
        return apply(decodedPacket, context);
    }

    private PacketRewriteResult apply(Object decodedPacket, C context) {
        if (!nativeType.isInstance(decodedPacket)) {
            throw new IncompatibleAdapterException(
                    "decoded type for " + typeName() + " was "
                            + decodedPacket.getClass().getName()
                            + ", expected " + nativeType.getName());
        }
        return Objects.requireNonNull(
                rewriter.apply(nativeType.cast(decodedPacket), context),
                "packet rewriter returned null");
    }
}
