package dev.voldechse.replayframework.adapter.paper.v26_3;

import dev.voldechse.replayframework.adapter.paper.protocol.PaperPacketRegistry;
import dev.voldechse.replayframework.adapter.paper.protocol.ProtocolSnapshot;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import java.util.Objects;

/** Builds the Paper 26.3 packet registry from the live registries and the 26.3 catalog. */
public final class Paper26_3PacketRegistry {

    static final String MINECRAFT_VERSION = "26.3";
    static final String CODEC_PREFIX = "paper-26.3/";

    private Paper26_3PacketRegistry() {
    }

    /** Discovers and classifies every live Paper 26.3 clientbound registry. */
    public static PaperPacketRegistry discover() {
        return fromSnapshot(new Paper26_3ProtocolIntrospector().inspect());
    }

    /** Creates a registry from a snapshot; used by the adapter and its tests. */
    static PaperPacketRegistry fromSnapshot(ProtocolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return PaperPacketRegistry.fromSnapshot(
                snapshot,
                MINECRAFT_VERSION,
                CODEC_PREFIX,
                Paper26_3ClientboundPacketCatalog.catalog());
    }

    static boolean explicitlyClassifies(String typeName) {
        return Paper26_3ClientboundPacketCatalog.explicitlyClassifies(typeName);
    }
}
