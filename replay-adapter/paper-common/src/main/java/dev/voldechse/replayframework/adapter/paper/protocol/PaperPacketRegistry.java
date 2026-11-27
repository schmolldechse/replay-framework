package dev.voldechse.replayframework.adapter.paper.protocol;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable packet registry built from a version adapter's protocol snapshot.
 *
 * <p>Every PLAY clientbound packet must have exactly one explicit definition in
 * the adapter's catalog, with the live packet ID; packets of the other phases
 * are connection control. The version adapter supplies its Minecraft version,
 * its codec key prefix and its catalog; this class holds no version
 * knowledge.</p>
 */
public final class PaperPacketRegistry implements PacketRegistry {

    private final PacketRegistry delegate;

    private PaperPacketRegistry(PacketRegistry delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Validates a snapshot against an adapter's catalog and builds the registry.
     *
     * @param snapshot live protocol snapshot
     * @param minecraftVersion the only Minecraft version the adapter accepts
     * @param codecKeyPrefix the adapter's codec key prefix, for example {@code "paper-26.2/"}
     * @param catalog the adapter's clientbound PLAY definitions
     * @return the registry
     * @throws IncompatibleAdapterException when the snapshot does not match the adapter
     */
    public static PaperPacketRegistry fromSnapshot(
            ProtocolSnapshot snapshot,
            String minecraftVersion,
            String codecKeyPrefix,
            ClientboundPacketDefinitionCatalog<?> catalog) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(codecKeyPrefix, "codecKeyPrefix");
        Objects.requireNonNull(catalog, "catalog");
        if (!minecraftVersion.equals(snapshot.minecraftVersion())) {
            throw new IncompatibleAdapterException("unsupported Paper Minecraft version");
        }

        List<PacketDescriptor> descriptors = new ArrayList<>(snapshot.clientboundPackets().size());
        for (ProtocolPacket packet : snapshot.clientboundPackets()) {
            validatePacket(packet);
            PacketClassification classification = classificationFor(packet, catalog);
            String expectedCodecKey = codecKeyPrefix + packet.typeName();
            if (!expectedCodecKey.equals(packet.codecKey())) {
                throw new IncompatibleAdapterException(
                        "Paper packet codec key mismatch for " + packet.typeName());
            }
            try {
                descriptors.add(new PacketDescriptor(
                        packet.typeName(),
                        packet.phase(),
                        packet.direction(),
                        packet.packetId(),
                        classification.disposition(),
                        classification.captureByDefault(),
                        classification.replayable(),
                        classification.checkpointRelevant(),
                        packet.codecKey(),
                        classification.playbackScope()));
            } catch (IllegalArgumentException exception) {
                throw new IncompatibleAdapterException(
                        "invalid Paper packet descriptor for " + packet.typeName(), exception);
            }
        }

        try {
            return new PaperPacketRegistry(PacketRegistry.of(descriptors));
        } catch (IllegalArgumentException exception) {
            throw new IncompatibleAdapterException(
                    "Paper clientbound packet registry is not unique", exception);
        }
    }

    private static PacketClassification classificationFor(
            ProtocolPacket packet,
            ClientboundPacketDefinitionCatalog<?> catalog) {
        if (packet.phase() != PacketPhase.PLAY) {
            return PacketClassification.control();
        }
        var definition = catalog.find(packet.typeName());
        if (definition.isEmpty()) {
            throw new IncompatibleAdapterException(
                    "Paper PLAY clientbound packet has no explicit definition: "
                            + packet.typeName());
        }
        var explicitDefinition = definition.orElseThrow();
        if (explicitDefinition.packetId() != packet.packetId()) {
            throw new IncompatibleAdapterException(
                    "Paper packet definition ID mismatch for " + packet.typeName()
                            + ": expected " + explicitDefinition.packetId()
                            + ", discovered " + packet.packetId());
        }
        return explicitDefinition.classification();
    }

    private static void validatePacket(ProtocolPacket packet) {
        if (packet == null
                || packet.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || packet.packetId() < 0) {
            throw new IncompatibleAdapterException(
                    "Paper snapshot contains an invalid clientbound packet");
        }
    }

    @Override
    public List<PacketDescriptor> descriptors() {
        return delegate.descriptors();
    }

    @Override
    public Optional<PacketDescriptor> find(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return delegate.find(phase, direction, packetId);
    }

    @Override
    public boolean captureAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return delegate.captureAllowed(phase, direction, packetId);
    }

    @Override
    public boolean replayAllowed(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {
        return delegate.replayAllowed(phase, direction, packetId);
    }

    @Override
    public String fingerprint() {
        return delegate.fingerprint();
    }
}
