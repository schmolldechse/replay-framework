package dev.voldechse.replayframework.adapter.paper.v26_3;

import dev.voldechse.replayframework.adapter.paper.protocol.PaperPacketRegistry;
import dev.voldechse.replayframework.adapter.paper.protocol.ProtocolSnapshot;
import dev.voldechse.replayframework.adapter.paper.protocol.ProtocolPacket;
import dev.voldechse.replayframework.adapter.StubPlaybackBridge;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Paper26_3PacketRegistryTest {

    @BeforeAll
    static void initializePaperRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyPaperClientboundPacketHasExactlyOneDescriptor() {
        ProtocolSnapshot snapshot =
                new Paper26_3ProtocolIntrospector().inspect();
        PaperPacketRegistry registry = Paper26_3PacketRegistry.fromSnapshot(snapshot);

        Set<PacketKey> expectedKeys = snapshot.clientboundPackets().stream()
                .map(PacketKey::from)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PacketKey> actualKeys = registry.descriptors().stream()
                .map(PacketKey::from)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(expectedKeys, actualKeys);
        assertEquals(snapshot.clientboundPackets().size(), registry.descriptors().size());

        Map<PacketKey, ProtocolPacket> expectedByKey =
                snapshot.clientboundPackets().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                PacketKey::from,
                                packetType -> packetType));
        for (PacketDescriptor descriptor : registry.descriptors()) {
            ProtocolPacket packetType = expectedByKey.get(PacketKey.from(descriptor));
            assertNotNull(packetType);
            assertEquals(packetType.typeName(), descriptor.typeName());
            assertEquals(packetType.codecKey(), descriptor.codecKey());
        }
    }

    @Test
    void unknownPlayPacketTypeFailsTheCompleteCatalogInvariant() {
        ProtocolSnapshot snapshot =
                new Paper26_3ProtocolIntrospector().inspect();
        List<ProtocolPacket> packets =
                new ArrayList<>(snapshot.clientboundPackets());
        packets.add(new ProtocolPacket(
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                "play.clientbound.synthetic.unknown",
                Integer.MAX_VALUE,
                "paper-26.3/play.clientbound.synthetic.unknown"));

        assertThrows(IncompatibleAdapterException.class, () ->
                Paper26_3PacketRegistry.fromSnapshot(
                        new ProtocolSnapshot(
                                snapshot.minecraftVersion(), snapshot.protocolVersion(), packets)));
    }

    @Test
    void rejectsExplicitDefinitionWhenTheLivePacketIdDiffers() {
        ProtocolSnapshot snapshot =
                new Paper26_3ProtocolIntrospector().inspect();
        List<ProtocolPacket> packets =
                snapshot.clientboundPackets().stream()
                        .map(packet -> packet.typeName().equals(
                                        "play.clientbound.minecraft:bundle_delimiter")
                                ? new ProtocolPacket(
                                        packet.phase(),
                                        packet.direction(),
                                        packet.typeName(),
                                        1_000_000,
                                        packet.codecKey())
                                : packet)
                        .toList();

        assertThrows(IncompatibleAdapterException.class, () ->
                Paper26_3PacketRegistry.fromSnapshot(
                        new ProtocolSnapshot(
                                snapshot.minecraftVersion(),
                                snapshot.protocolVersion(),
                                packets)));
    }

    @Test
    void controlAndUnsupportedPacketsAreCapturedButNeverReplayed() {
        ProtocolSnapshot snapshot =
                new Paper26_3ProtocolIntrospector().inspect();
        PaperPacketRegistry registry = Paper26_3PacketRegistry.fromSnapshot(snapshot);

        List<PacketDescriptor> blocked = registry.descriptors().stream()
                .filter(descriptor -> descriptor.disposition() == PacketDisposition.CONTROL
                        || descriptor.disposition() == PacketDisposition.UNSUPPORTED)
                .toList();

        assertFalse(blocked.isEmpty());
        for (PacketDescriptor descriptor : blocked) {
            assertTrue(descriptor.captureByDefault());
            assertFalse(descriptor.replayable());
            assertFalse(descriptor.checkpointRelevant());
            assertTrue(registry.captureAllowed(
                    descriptor.phase(), descriptor.direction(), descriptor.packetId()));
            assertFalse(registry.replayAllowed(
                    descriptor.phase(), descriptor.direction(), descriptor.packetId()));
        }
    }

    @Test
    void registryFingerprintIsStableForEquivalentInputOrder() {
        ProtocolSnapshot snapshot =
                new Paper26_3ProtocolIntrospector().inspect();
        PaperPacketRegistry first = Paper26_3PacketRegistry.fromSnapshot(snapshot);

        List<ProtocolPacket> reversed =
                new ArrayList<>(snapshot.clientboundPackets());
        java.util.Collections.reverse(reversed);
        PaperPacketRegistry second = Paper26_3PacketRegistry.fromSnapshot(
                new ProtocolSnapshot(
                        snapshot.minecraftVersion(), snapshot.protocolVersion(), reversed));

        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void paperAdapterCarriesExactDescriptorAndFingerprint() {
        ProtocolSnapshot snapshot =
                new Paper26_3ProtocolIntrospector().inspect();
        PaperPacketRegistry registry = Paper26_3PacketRegistry.fromSnapshot(snapshot);
        CaptureBridge captureBridge = noOpCaptureBridge();
        CheckpointEncoder checkpointEncoder = request -> CompletableFuture.failedFuture(
                new AssertionError("test checkpoint encoder must not be invoked"));
        PlaybackBridge playbackBridge = noOpPlaybackBridge();

        Paper26_3ReplayAdapter adapter = new Paper26_3ReplayAdapter(
                captureBridge,
                checkpointEncoder,
                ignored -> playbackBridge);

        assertEquals("paper-26.3", adapter.descriptor().adapterId());
        assertEquals(snapshot.protocolVersion(), adapter.descriptor().protocolVersion());
        assertEquals(5, adapter.descriptor().adapterFormatRevision());
        assertEquals(registry.fingerprint(), adapter.descriptor().registryFingerprint());
        assertEquals(registry.fingerprint(), adapter.packetRegistry().fingerprint());
        adapter.verifyDescriptorAndRegistry();
    }

    private static CaptureBridge noOpCaptureBridge() {
        return new CaptureBridge() {
            @Override
            public void install(PacketSink sink, FailureHandler failureHandler) {
            }

            @Override
            public void uninstall() {
            }

            @Override
            public boolean installed() {
                return false;
            }
        };
    }

    private static PlaybackBridge noOpPlaybackBridge() {
        return new StubPlaybackBridge() {
            @Override
            public void setIdentityContext(dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext context) {
                java.util.Objects.requireNonNull(context);
            }

            @Override
            public void send(dev.voldechse.replayframework.format.RawPacketFrame frame) {
            }

            @Override
            public void resetView() {
            }

            @Override
            public void close() {
            }
        };
    }

    private record PacketKey(
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId) {

        private static PacketKey from(ProtocolPacket packetType) {
            return new PacketKey(packetType.phase(), packetType.direction(), packetType.packetId());
        }

        private static PacketKey from(PacketDescriptor descriptor) {
            return new PacketKey(descriptor.phase(), descriptor.direction(), descriptor.packetId());
        }
    }
}
