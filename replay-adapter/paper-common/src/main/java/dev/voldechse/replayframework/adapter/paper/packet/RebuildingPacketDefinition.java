package dev.voldechse.replayframework.adapter.paper.packet;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.Objects;

/**
 * Typed template for packets that are read into an immutable semantic value,
 * rewritten with session state and rebuilt as a new native packet.
 */
public abstract class RebuildingPacketDefinition<C, P, V> extends PacketDefinition<C> {

    private final Class<P> nativeType;

    protected RebuildingPacketDefinition(
            String identifier,
            PacketClassification classification,
            Class<P> nativeType) {
        super(identifier, classification);
        this.nativeType = Objects.requireNonNull(nativeType, "nativeType");
        var scope = classification.playbackScope();
        if (scope != dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.SAFE
                && scope != dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES) {
            throw new IllegalArgumentException(
                    "rebuilding packet definitions must be safe or identity-bearing");
        }
    }

    @Override
    protected final PacketRewriteResult rewriteSafe(Object decodedPacket, C context) {
        return transform(decodedPacket, context);
    }

    @Override
    protected final PacketRewriteResult rewriteIdentities(Object decodedPacket, C context) {
        return transform(decodedPacket, context);
    }

    protected abstract V read(P packet);

    protected abstract ValueRewriteResult<V> rewrite(V value, C context);

    protected abstract P rebuild(V value);

    private PacketRewriteResult transform(Object decodedPacket, C context) {
        if (!nativeType.isInstance(decodedPacket)) {
            throw new IncompatibleAdapterException(
                    "decoded type for " + typeName() + " was "
                            + decodedPacket.getClass().getName()
                            + ", expected " + nativeType.getName());
        }
        V value = Objects.requireNonNull(read(nativeType.cast(decodedPacket)), "read returned null");
        ValueRewriteResult<V> result = Objects.requireNonNull(
                rewrite(value, context), "rewrite returned null");
        return switch (result.decision()) {
            case SEND -> PacketRewriteResult.send(Objects.requireNonNull(
                    rebuild(result.value().orElseThrow()), "rebuild returned null"));
            case DROP_LOCAL -> PacketRewriteResult.drop(result.reason());
            case FAIL -> PacketRewriteResult.fail(result.reason());
        };
    }
}
