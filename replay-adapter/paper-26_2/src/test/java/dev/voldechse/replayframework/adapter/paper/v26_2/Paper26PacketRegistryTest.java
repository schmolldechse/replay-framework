package dev.voldechse.replayframework.adapter.paper.v26_2;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Paper26PacketRegistryTest {

    @BeforeAll
    static void initializePaperRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyPaperPlayClientboundPacketHasExactlyOneDescriptor() {
        Paper26ProtocolIntrospector.ProtocolSnapshot snapshot =
                new Paper26ProtocolIntrospector().inspect();
        Paper26PacketRegistry registry = Paper26PacketRegistry.fromSnapshot(snapshot);

        Set<PacketKey> expectedKeys = snapshot.clientboundPlayPackets().stream()
                .map(PacketKey::from)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PacketKey> actualKeys = registry.descriptors().stream()
                .map(PacketKey::from)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(expectedKeys, actualKeys);
        assertEquals(snapshot.clientboundPlayPackets().size(), registry.descriptors().size());

        Map<PacketKey, Paper26ProtocolIntrospector.PacketType> expectedByKey =
                snapshot.clientboundPlayPackets().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                PacketKey::from,
                                packetType -> packetType));
        for (PacketDescriptor descriptor : registry.descriptors()) {
            Paper26ProtocolIntrospector.PacketType packetType = expectedByKey.get(PacketKey.from(descriptor));
            assertNotNull(packetType);
            assertEquals(packetType.typeName(), descriptor.typeName());
            assertEquals(packetType.codecKey(), descriptor.codecKey());
        }
    }

    @Test
    void unknownPacketTypeRejectsAdapterConstruction() {
        Paper26ProtocolIntrospector.ProtocolSnapshot snapshot =
                new Paper26ProtocolIntrospector().inspect();
        List<Paper26ProtocolIntrospector.PacketType> packets =
                new ArrayList<>(snapshot.clientboundPlayPackets());
        packets.add(new Paper26ProtocolIntrospector.PacketType(
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                "play.clientbound.synthetic.unknown",
                Integer.MAX_VALUE,
                "paper-26.2/play.clientbound.synthetic.unknown"));

        assertThrows(
                IncompatibleAdapterException.class,
                () -> Paper26PacketRegistry.fromSnapshot(new Paper26ProtocolIntrospector.ProtocolSnapshot(
                        snapshot.minecraftVersion(),
                        snapshot.protocolVersion(),
                        packets)));
    }

    @Test
    void controlAndUnsupportedPacketsAreNeverAllowed() {
        Paper26ProtocolIntrospector.ProtocolSnapshot snapshot =
                new Paper26ProtocolIntrospector().inspect();
        Paper26PacketRegistry registry = Paper26PacketRegistry.fromSnapshot(snapshot);

        List<PacketDescriptor> blocked = registry.descriptors().stream()
                .filter(descriptor -> descriptor.disposition() == PacketDisposition.CONTROL
                        || descriptor.disposition() == PacketDisposition.UNSUPPORTED)
                .toList();

        assertFalse(blocked.isEmpty());
        for (PacketDescriptor descriptor : blocked) {
            assertFalse(descriptor.captureByDefault());
            assertFalse(descriptor.replayable());
            assertFalse(descriptor.checkpointRelevant());
            assertFalse(registry.captureAllowed(
                    descriptor.phase(), descriptor.direction(), descriptor.packetId()));
            assertFalse(registry.replayAllowed(
                    descriptor.phase(), descriptor.direction(), descriptor.packetId()));
        }
    }

    @Test
    void registryFingerprintIsStableForEquivalentInputOrder() {
        Paper26ProtocolIntrospector.ProtocolSnapshot snapshot =
                new Paper26ProtocolIntrospector().inspect();
        Paper26PacketRegistry first = Paper26PacketRegistry.fromSnapshot(snapshot);

        List<Paper26ProtocolIntrospector.PacketType> reversed =
                new ArrayList<>(snapshot.clientboundPlayPackets());
        java.util.Collections.reverse(reversed);
        Paper26PacketRegistry second = Paper26PacketRegistry.fromSnapshot(
                new Paper26ProtocolIntrospector.ProtocolSnapshot(
                        snapshot.minecraftVersion(), snapshot.protocolVersion(), reversed));

        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void paperAdapterCarriesExactDescriptorAndFingerprint() {
        Paper26ProtocolIntrospector.ProtocolSnapshot snapshot =
                new Paper26ProtocolIntrospector().inspect();
        Paper26PacketRegistry registry = Paper26PacketRegistry.fromSnapshot(snapshot);
        CaptureBridge captureBridge = noOpCaptureBridge();
        CheckpointEncoder checkpointEncoder = request -> CompletableFuture.failedFuture(
                new AssertionError("test checkpoint encoder must not be invoked"));
        PlaybackBridge playbackBridge = noOpPlaybackBridge();

        Paper26ReplayAdapter adapter = new Paper26ReplayAdapter(
                captureBridge,
                checkpointEncoder,
                ignored -> playbackBridge);

        assertEquals("paper-26.2", adapter.descriptor().adapterId());
        assertEquals(snapshot.protocolVersion(), adapter.descriptor().protocolVersion());
        assertEquals(1, adapter.descriptor().adapterFormatRevision());
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
        return new PlaybackBridge() {
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

        private static PacketKey from(Paper26ProtocolIntrospector.PacketType packetType) {
            return new PacketKey(packetType.phase(), packetType.direction(), packetType.packetId());
        }

        private static PacketKey from(PacketDescriptor descriptor) {
            return new PacketKey(descriptor.phase(), descriptor.direction(), descriptor.packetId());
        }
    }
}
