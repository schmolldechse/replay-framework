package dev.voldechse.replayframework.adapter.paper.v26_2;

import static org.junit.jupiter.api.Assertions.*;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validates the real protocol inventory, including newly added vanilla packets. */
class PacketCoverageTest {
    @Test
    void classifiesEveryPacketAndCoversEveryEssentialPlaybackFamily() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var registry = Paper26PacketRegistry.discover();
        for (var descriptor : registry.descriptors()) {
            if (descriptor.phase() == PacketPhase.PLAY) {
                assertTrue(Paper26PacketRegistry.explicitlyClassifies(descriptor.typeName()),
                        "Unreviewed packet: " + descriptor.typeName());
            } else {
                assertFalse(descriptor.replayable(), descriptor.typeName());
            }
            if (descriptor.playbackScope() == PacketDescriptor.PlaybackScope.VIEWER_LOCAL) {
                assertFalse(descriptor.checkpointRelevant(), descriptor.typeName());
            }
        }
        for (String essential : List.of("level_chunk_with_light", "light_update", "block_update",
                "block_entity_data", "add_entity", "remove_entities", "set_entity_data", "set_equipment",
                "set_passengers", "set_entity_link", "move_entity_pos", "entity_position_sync",
                "move_minecart_along_track", "animate", "entity_event", "sound_entity", "damage_event",
                "player_info_update", "player_info_remove", "player_chat", "explode")) {
            assertTrue(registry.descriptors().stream().anyMatch(packet ->
                    packet.typeName().equals("play.clientbound.minecraft:" + essential) && packet.replayable()), essential);
        }
        assertEquals("d9d610afb1ff45fa2a97302337053d3f9643e6d09a685cf32652bc6678ffd744",
                registry.fingerprint(), "Review packet policy and adapter documentation when the registry changes");
    }
}
