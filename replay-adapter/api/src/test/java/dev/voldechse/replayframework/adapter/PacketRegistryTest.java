package dev.voldechse.replayframework.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.format.PacketPhase;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PacketRegistryTest {

    @Test
    void controlAndUnsupportedPacketsAreNeverAllowed() {
        PacketRegistry registry = PacketRegistry.of(List.of(
                descriptor(PacketDescriptor.Direction.CLIENTBOUND, 1, PacketDisposition.CONTROL),
                descriptor(PacketDescriptor.Direction.CLIENTBOUND, 2, PacketDisposition.UNSUPPORTED)));

        assertFalse(registry.captureAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 1));
        assertFalse(registry.replayAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 1));
        assertFalse(registry.captureAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 2));
        assertFalse(registry.replayAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 2));
    }

    @Test
    void serverboundPacketsAreNeverAllowed() {
        PacketRegistry registry = PacketRegistry.of(List.of(
                descriptor(PacketDescriptor.Direction.SERVERBOUND, 3, PacketDisposition.STATEFUL)));

        assertTrue(registry.find(PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, 3).isPresent());
        assertFalse(registry.captureAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, 3));
        assertFalse(registry.replayAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, 3));
    }

    @Test
    void duplicatePacketKeysAreRejected() {
        PacketDescriptor first = descriptor(PacketDescriptor.Direction.CLIENTBOUND, 4, PacketDisposition.STATEFUL);
        PacketDescriptor duplicate = new PacketDescriptor(
                "different.packet",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                4,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "different.codec");

        assertThrows(IllegalArgumentException.class, () -> PacketRegistry.of(List.of(first, duplicate)));
    }

    @Test
    void fingerprintIsIndependentOfInputOrder() {
        List<PacketDescriptor> descriptors = List.of(
                descriptor(PacketDescriptor.Direction.CLIENTBOUND, 8, PacketDisposition.STATEFUL),
                descriptor(PacketDescriptor.Direction.CLIENTBOUND, 2, PacketDisposition.EPHEMERAL),
                descriptor(PacketDescriptor.Direction.CLIENTBOUND, 5, PacketDisposition.CONFIGURABLE));

        PacketRegistry first = PacketRegistry.of(descriptors);
        PacketRegistry second = PacketRegistry.of(List.of(descriptors.get(2), descriptors.get(0), descriptors.get(1)));

        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void fingerprintChangesWhenRelevantDescriptorDataChanges() {
        PacketDescriptor original = descriptor(
                PacketDescriptor.Direction.CLIENTBOUND, 9, PacketDisposition.STATEFUL);
        PacketDescriptor changedCodec = new PacketDescriptor(
                original.typeName(),
                original.phase(),
                original.direction(),
                original.packetId(),
                original.disposition(),
                original.captureByDefault(),
                original.replayable(),
                original.checkpointRelevant(),
                "codec.changed");

        assertNotEquals(
                PacketRegistry.of(List.of(original)).fingerprint(),
                PacketRegistry.of(List.of(changedCodec)).fingerprint());
    }

    @Test
    void unknownPacketLookupIsRejectedWithoutGuessing() {
        PacketRegistry registry = PacketRegistry.of(List.of(
                descriptor(PacketDescriptor.Direction.CLIENTBOUND, 10, PacketDisposition.STATEFUL)));

        Optional<PacketDescriptor> unknown = registry.find(
                PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 99);

        assertTrue(unknown.isEmpty());
        assertFalse(registry.captureAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 99));
        assertFalse(registry.replayAllowed(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 99));
    }

    @Test
    void descriptorsAreCanonicalAndImmutable() {
        PacketDescriptor play = descriptor(PacketDescriptor.Direction.CLIENTBOUND, 1, PacketDisposition.STATEFUL);
        PacketDescriptor configuration = new PacketDescriptor(
                "configuration.packet",
                PacketPhase.CONFIGURATION,
                PacketDescriptor.Direction.CLIENTBOUND,
                3,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "configuration.codec");
        PacketRegistry registry = PacketRegistry.of(List.of(play, configuration));

        assertEquals(List.of(configuration, play), registry.descriptors());
        assertThrows(UnsupportedOperationException.class, () -> registry.descriptors().clear());
    }

    private static PacketDescriptor descriptor(
            PacketDescriptor.Direction direction,
            int packetId,
            PacketDisposition disposition) {
        boolean enabled = disposition != PacketDisposition.CONTROL
                && disposition != PacketDisposition.UNSUPPORTED
                && direction == PacketDescriptor.Direction.CLIENTBOUND;
        boolean checkpointRelevant = disposition == PacketDisposition.STATEFUL && enabled;
        return new PacketDescriptor(
                "packet." + packetId + "." + direction.name().toLowerCase(),
                PacketPhase.PLAY,
                direction,
                packetId,
                disposition,
                enabled,
                enabled,
                checkpointRelevant,
                "codec." + packetId + "." + direction.name().toLowerCase());
    }
}
