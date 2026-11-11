package dev.voldechse.replayframework.adapter.paper.v26_3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.Paper26_3PacketCatalog;
import dev.voldechse.replayframework.format.PacketPhase;
import org.junit.jupiter.api.Test;

/** Verifies explicit coverage while packet implementations are added incrementally. */
class PacketCoverageTest {

    @Test
    void classifiesOnlyImplementedPacketsExplicitly() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        assertEquals(
                java.util.List.of(
                        "play.clientbound.minecraft:bundle_delimiter",
                        "play.clientbound.minecraft:add_entity",
                        "play.clientbound.minecraft:animate",
                        "play.clientbound.minecraft:award_stats",
                        "play.clientbound.minecraft:block_changed_ack",
                        "play.clientbound.minecraft:block_destruction"),
                Paper26_3PacketCatalog.catalog().definitions().stream()
                        .map(definition -> definition.typeName())
                        .toList());
        var registry = Paper26_3PacketRegistry.discover();
        for (var descriptor : registry.descriptors()) {
            if (descriptor.phase() == PacketPhase.PLAY) {
                boolean bundleDelimiter =
                        descriptor.typeName().equals("play.clientbound.minecraft:bundle_delimiter");
                boolean addEntity =
                        descriptor.typeName().equals("play.clientbound.minecraft:add_entity");
                boolean animate =
                        descriptor.typeName().equals("play.clientbound.minecraft:animate");
                boolean awardStats =
                        descriptor.typeName().equals("play.clientbound.minecraft:award_stats");
                boolean blockChangedAck =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_changed_ack");
                boolean blockDestruction =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_destruction");
                PacketDisposition expectedDisposition;
                if (bundleDelimiter || blockChangedAck) {
                    expectedDisposition = PacketDisposition.CONTROL;
                } else if (addEntity) {
                    expectedDisposition = PacketDisposition.STATEFUL;
                } else if (animate || blockDestruction) {
                    expectedDisposition = PacketDisposition.EPHEMERAL;
                } else if (awardStats) {
                    expectedDisposition = PacketDisposition.STATEFUL;
                } else {
                    expectedDisposition = PacketDisposition.UNSUPPORTED;
                }
                assertEquals(expectedDisposition, descriptor.disposition());
                assertTrue(descriptor.captureByDefault());
                assertEquals(
                        addEntity || animate || awardStats || blockDestruction,
                        descriptor.replayable());
                assertEquals(addEntity, descriptor.checkpointRelevant());
                if (addEntity || animate || blockDestruction) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                            descriptor.playbackScope());
                } else if (awardStats) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                            descriptor.playbackScope());
                } else {
                    assertFalse(descriptor.replayable());
                    assertFalse(descriptor.checkpointRelevant());
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.UNSAFE,
                            descriptor.playbackScope());
                }
                assertEquals(
                        bundleDelimiter
                                || addEntity
                                || animate
                                || awardStats
                                || blockChangedAck
                                || blockDestruction,
                        Paper26_3PacketRegistry.explicitlyClassifies(descriptor.typeName()));
            }
        }
    }
}
