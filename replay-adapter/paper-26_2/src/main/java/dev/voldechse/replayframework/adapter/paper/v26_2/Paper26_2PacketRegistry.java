package dev.voldechse.replayframework.adapter.paper.v26_2;

import dev.voldechse.replayframework.adapter.paper.protocol.PaperPacketRegistry;
import dev.voldechse.replayframework.adapter.paper.protocol.ProtocolSnapshot;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import java.util.Objects;

/** Builds the Paper 26.2 packet registry from the live registries and the 26.2 catalog. */
public final class Paper26_2PacketRegistry {

    static final String MINECRAFT_VERSION = "26.2";
    static final String CODEC_PREFIX = "paper-26.2/";

    private Paper26_2PacketRegistry() {
    }

    /** Discovers and classifies every live Paper 26.2 clientbound registry. */
    public static PaperPacketRegistry discover() {
        return fromSnapshot(new Paper26_2ProtocolIntrospector().inspect());
    }

    /** Creates a registry from a snapshot; used by the adapter and its tests. */
    static PaperPacketRegistry fromSnapshot(ProtocolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return PaperPacketRegistry.fromSnapshot(
                snapshot,
                MINECRAFT_VERSION,
                CODEC_PREFIX,
                Paper26_2ClientboundPacketCatalog.catalog());
    }

    static boolean explicitlyClassifies(String typeName) {
        return Paper26_2ClientboundPacketCatalog.explicitlyClassifies(typeName);
    }
}
