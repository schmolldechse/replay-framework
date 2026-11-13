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
                        "play.clientbound.minecraft:block_destruction",
                        "play.clientbound.minecraft:block_entity_data",
                        "play.clientbound.minecraft:block_event",
                        "play.clientbound.minecraft:block_update",
                        "play.clientbound.minecraft:boss_event",
                        "play.clientbound.minecraft:change_difficulty",
                        "play.clientbound.minecraft:chunk_batch_finished",
                        "play.clientbound.minecraft:chunk_batch_start",
                        "play.clientbound.minecraft:chunks_biomes",
                        "play.clientbound.minecraft:clear_titles"),
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
                boolean blockEntityData =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_entity_data");
                boolean blockEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_event");
                boolean blockUpdate =
                        descriptor.typeName().equals("play.clientbound.minecraft:block_update");
                boolean bossEvent =
                        descriptor.typeName().equals("play.clientbound.minecraft:boss_event");
                boolean changeDifficulty =
                        descriptor.typeName().equals("play.clientbound.minecraft:change_difficulty");
                boolean chunkBatchFinished = descriptor.typeName()
                        .equals("play.clientbound.minecraft:chunk_batch_finished");
                boolean chunkBatchStart =
                        descriptor.typeName().equals("play.clientbound.minecraft:chunk_batch_start");
                boolean chunksBiomes =
                        descriptor.typeName().equals("play.clientbound.minecraft:chunks_biomes");
                boolean clearTitles =
                        descriptor.typeName().equals("play.clientbound.minecraft:clear_titles");
                PacketDisposition expectedDisposition;
                if (bundleDelimiter || blockChangedAck || chunkBatchFinished || chunkBatchStart) {
                    expectedDisposition = PacketDisposition.CONTROL;
                } else if (addEntity
                        || blockEntityData
                        || blockUpdate
                        || bossEvent
                        || changeDifficulty
                        || chunksBiomes
                        || clearTitles) {
                    expectedDisposition = PacketDisposition.STATEFUL;
                } else if (animate || blockDestruction || blockEvent) {
                    expectedDisposition = PacketDisposition.EPHEMERAL;
                } else if (awardStats) {
                    expectedDisposition = PacketDisposition.STATEFUL;
                } else {
                    expectedDisposition = PacketDisposition.UNSUPPORTED;
                }
                assertEquals(expectedDisposition, descriptor.disposition());
                assertTrue(descriptor.captureByDefault());
                assertEquals(
                        addEntity
                                || animate
                                || awardStats
                                || blockDestruction
                                || blockEntityData
                                || blockEvent
                                || blockUpdate
                                || bossEvent
                                || changeDifficulty
                                || chunksBiomes
                                || clearTitles,
                        descriptor.replayable());
                assertEquals(
                        addEntity || blockEntityData || blockUpdate || bossEvent || chunksBiomes,
                        descriptor.checkpointRelevant());
                if (addEntity || animate || blockDestruction || bossEvent) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                            descriptor.playbackScope());
                } else if (awardStats || changeDifficulty || clearTitles) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                            descriptor.playbackScope());
                } else if (blockEntityData || blockEvent || blockUpdate || chunksBiomes) {
                    assertEquals(
                            dev.voldechse.replayframework.adapter.PacketDescriptor.PlaybackScope.SAFE,
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
                                || blockDestruction
                                || blockEntityData
                                || blockEvent
                                || blockUpdate
                                || bossEvent
                                || changeDifficulty
                                || chunkBatchFinished
                                || chunkBatchStart
                                || chunksBiomes
                                || clearTitles,
                        Paper26_3PacketRegistry.explicitlyClassifies(descriptor.typeName()));
            }
        }
    }
}
