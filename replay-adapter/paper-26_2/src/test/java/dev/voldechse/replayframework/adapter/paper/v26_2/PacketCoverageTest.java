package dev.voldechse.replayframework.adapter.paper.v26_2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import org.junit.jupiter.api.Test;

/** Verifies explicit coverage while packet implementations are added incrementally. */
class PacketCoverageTest {

    @Test
    void classifiesOnlyBundleDelimiterAsControl() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        assertEquals(
                java.util.List.of("play.clientbound.minecraft:bundle_delimiter"),
                Paper26_2PacketCatalog.catalog().definitions().stream()
                        .map(definition -> definition.typeName())
                        .toList());
        var registry = Paper26_2PacketRegistry.discover();
        for (var descriptor : registry.descriptors()) {
            if (descriptor.phase() == PacketPhase.PLAY) {
                boolean bundleDelimiter =
                        descriptor.typeName().equals("play.clientbound.minecraft:bundle_delimiter");
                assertEquals(
                        bundleDelimiter ? PacketDisposition.CONTROL : PacketDisposition.UNSUPPORTED,
                        descriptor.disposition());
                assertTrue(descriptor.captureByDefault());
                assertFalse(descriptor.replayable());
                assertFalse(descriptor.checkpointRelevant());
                assertEquals(
                        bundleDelimiter,
                        Paper26_2PacketRegistry.explicitlyClassifies(descriptor.typeName()));
            }
        }
    }
}
