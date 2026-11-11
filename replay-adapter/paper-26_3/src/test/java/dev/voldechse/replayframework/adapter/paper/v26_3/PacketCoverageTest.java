package dev.voldechse.replayframework.adapter.paper.v26_3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3PacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import org.junit.jupiter.api.Test;

/** Verifies the deliberate empty-catalog state. */
class PacketCoverageTest {

    @Test
    void leavesEveryPlayPacketUnsupportedUntilImplementedExplicitly() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        assertTrue(Paper26_3PacketCatalog.catalog().definitions().isEmpty());
        var registry = Paper26_3PacketRegistry.discover();
        for (var descriptor : registry.descriptors()) {
            if (descriptor.phase() == PacketPhase.PLAY) {
                assertEquals(PacketDisposition.UNSUPPORTED, descriptor.disposition());
                assertTrue(descriptor.captureByDefault());
                assertFalse(descriptor.replayable());
                assertFalse(descriptor.checkpointRelevant());
                assertFalse(Paper26_3PacketRegistry.explicitlyClassifies(descriptor.typeName()));
            }
        }
    }
}
