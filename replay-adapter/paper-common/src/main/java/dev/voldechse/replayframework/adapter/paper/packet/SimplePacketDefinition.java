package dev.voldechse.replayframework.adapter.paper.packet;

/** Definition used when descriptor policy fully describes packet behavior. */
public final class SimplePacketDefinition<C> extends PacketDefinition<C> {

    public SimplePacketDefinition(String identifier, PacketClassification classification) {
        super(identifier, classification);
        if (classification.playbackScope()
                == dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES) {
            throw new IllegalArgumentException(
                    "identity-bearing packets require a concrete definition");
        }
    }
}
