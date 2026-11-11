package dev.voldechse.replayframework.adapter.paper.v26_3;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.Paper26_3PacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable Paper 26.3 implementation of the adapter packet registry. */
public final class Paper26_3PacketRegistry implements PacketRegistry {

    private static final String CODEC_PREFIX = "paper-26.3/";

    private final PacketRegistry delegate;

    private Paper26_3PacketRegistry(PacketRegistry delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Discovers and classifies every live Paper 26.3 clientbound registry. */
    public static Paper26_3PacketRegistry discover() {
        return fromSnapshot(new Paper26_3ProtocolIntrospector().inspect());
    }

    /** Creates a registry from the NMS-free snapshot used by the focused adapter test. */
    static Paper26_3PacketRegistry fromSnapshot(
            Paper26_3ProtocolIntrospector.ProtocolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!"26.3".equals(snapshot.minecraftVersion())) {
            throw incompatible("unsupported Paper Minecraft version", null);
        }

        List<PacketDescriptor> descriptors = new ArrayList<>(snapshot.clientboundPackets().size());
        for (Paper26_3ProtocolIntrospector.PacketType packet : snapshot.clientboundPackets()) {
            validatePacket(packet);
            PacketClassification classification = classificationFor(packet);
            String expectedCodecKey = CODEC_PREFIX + packet.typeName();
            if (!expectedCodecKey.equals(packet.codecKey())) {
                throw incompatible("Paper packet codec key mismatch for " + packet.typeName(), null);
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
                throw incompatible("invalid Paper packet descriptor for " + packet.typeName(), exception);
            }
        }

        try {
            return new Paper26_3PacketRegistry(PacketRegistry.of(descriptors));
        } catch (IllegalArgumentException exception) {
            throw incompatible("Paper clientbound packet registry is not unique", exception);
        }
    }

    static boolean explicitlyClassifies(String typeName) {
        return Paper26_3PacketCatalog.explicitlyClassifies(typeName);
    }

    private static PacketClassification classificationFor(
            Paper26_3ProtocolIntrospector.PacketType packet) {
        if (packet.phase() != PacketPhase.PLAY) {
            return PacketClassification.control();
        }
        var definition = Paper26_3PacketCatalog.catalog().find(packet.typeName());
        if (definition.isEmpty()) {
            return PacketClassification.unsupported();
        }
        var explicitDefinition = definition.orElseThrow();
        if (explicitDefinition.packetId() != packet.packetId()) {
            throw incompatible(
                    "Paper packet definition ID mismatch for " + packet.typeName()
                            + ": expected " + explicitDefinition.packetId()
                            + ", discovered " + packet.packetId(),
                    null);
        }
        return explicitDefinition.classification();
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

    private static void validatePacket(Paper26_3ProtocolIntrospector.PacketType packet) {
        if (packet == null
                || packet.direction() != PacketDescriptor.Direction.CLIENTBOUND
                || packet.packetId() < 0) {
            throw incompatible("Paper snapshot contains an invalid clientbound packet", null);
        }
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }
}
