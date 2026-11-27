package dev.voldechse.replayframework.adapter.paper.playback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ViewerGateTest {

    private static final PacketDescriptor REPLAYABLE = descriptor(
            "minecraft:test_replayable", 7, PacketDisposition.STATEFUL, true, PacketDescriptor.PlaybackScope.SAFE);
    private static final PacketDescriptor CONTROL = new PacketDescriptor(
            "minecraft:test_control", PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 8,
            PacketDisposition.CONTROL, false, false, false, "test/minecraft:test_control");

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    @Test
    void suppressesReplayableLivePacketsWhileIsolating() {
        EmbeddedChannel channel = isolating(gate(REPLAYABLE));

        assertFalse(channel.writeOutbound(new TestPacket(7)));
        assertTrue(channel.outboundMessages().isEmpty());
        assertTrue(channel.isOpen());
        assertNull(failure.get());
    }

    @Test
    void suppressesLiveDamageEventsWhileIsolating() {
        PacketDescriptor damageEvent = descriptor("play.clientbound.minecraft:damage_event", 25,
                PacketDisposition.EPHEMERAL, true, PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
        EmbeddedChannel channel = isolating(gate(damageEvent));

        assertFalse(channel.writeOutbound(new TestPacket(25)));
        assertTrue(channel.outboundMessages().isEmpty());
        assertNull(failure.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void forwardsLivePacketsUntilIsolationIsActivated() {
        ViewerGate gate = gate(REPLAYABLE);
        EmbeddedChannel channel = new EmbeddedChannel(gate);

        TestPacket teleportChunk = new TestPacket(7);
        assertTrue(channel.writeOutbound(teleportChunk));
        assertSame(teleportChunk, channel.readOutbound());

        gate.activateIsolation();
        channel.runPendingTasks();
        assertFalse(channel.writeOutbound(new TestPacket(7)));
        assertTrue(channel.outboundMessages().isEmpty());
        assertNull(failure.get());
    }

    @Test
    void aWorldChangeLetsTheLiveWorldPassUntilTheViewIsRebuilt() {
        PacketDescriptor respawn = descriptor("play.clientbound.minecraft:respawn", 82,
                PacketDisposition.STATEFUL, false, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
        ViewerGate gate = gate(REPLAYABLE, respawn);
        EmbeddedChannel channel = isolating(gate);

        TestPacket worldChange = new TestPacket(82);
        assertTrue(channel.writeOutbound(worldChange));
        assertSame(worldChange, channel.readOutbound());
        TestPacket liveChunk = new TestPacket(7);
        assertTrue(channel.writeOutbound(liveChunk));
        assertSame(liveChunk, channel.readOutbound());

        gate.resumeIsolation();
        assertFalse(channel.writeOutbound(new TestPacket(7)));
        assertTrue(channel.outboundMessages().isEmpty());
        assertNull(failure.get());
    }

    @Test
    void forwardsControlPacketsAndUnknownMessages() {
        EmbeddedChannel channel = isolating(gate(REPLAYABLE, CONTROL));

        TestPacket controlPacket = new TestPacket(8);
        assertTrue(channel.writeOutbound(controlPacket));
        assertSame(controlPacket, channel.readOutbound());
        assertTrue(channel.writeOutbound("not a packet"));
        assertSame("not a packet", channel.readOutbound());
        assertNull(failure.get());
    }

    @Test
    void liveObserverStateAlwaysPassesTheGate() {
        for (String name : List.of(
                "set_health", "player_position", "player_abilities", "set_player_inventory", "set_title_text",
                "clear_titles", "command_suggestions", "commands", "container_close", "container_set_content",
                "container_set_data", "container_set_slot", "cooldown", "custom_chat_completions",
                "custom_payload")) {
            EmbeddedChannel channel = isolating(gate(descriptor("play.clientbound.minecraft:" + name, 9,
                    PacketDisposition.STATEFUL, false, PacketDescriptor.PlaybackScope.VIEWER_LOCAL)));
            try {
                TestPacket livePacket = new TestPacket(9);
                assertTrue(channel.writeOutbound(livePacket), name);
                assertSame(livePacket, channel.readOutbound(), name);
                assertNull(failure.get());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void livePlayerChatRemainsVisibleWhileIsolating() {
        PacketDescriptor playerChat = descriptor("play.clientbound.minecraft:player_chat", 65,
                PacketDisposition.CONFIGURABLE, false, PacketDescriptor.PlaybackScope.SAFE);
        EmbeddedChannel channel = isolating(gate(playerChat));

        TestPacket liveChat = new TestPacket(65);
        assertTrue(channel.writeOutbound(liveChat));
        assertSame(liveChat, channel.readOutbound());
        assertNull(failure.get());
        channel.finishAndReleaseAll();
    }

    /** A gate whose registry holds these descriptors; test packets are classified by their ID. */
    private ViewerGate gate(PacketDescriptor... descriptors) {
        PacketRegistry registry = PacketRegistry.of(List.of(descriptors));
        return new ViewerGate(
                registry,
                message -> message instanceof TestPacket packet
                        ? registry.find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, packet.packetId())
                        : Optional.empty(),
                () -> { },
                failure::set);
    }

    private static EmbeddedChannel isolating(ViewerGate gate) {
        EmbeddedChannel channel = new EmbeddedChannel(gate);
        gate.activateIsolation();
        return channel;
    }

    private static PacketDescriptor descriptor(
            String typeName, int id, PacketDisposition disposition, boolean checkpointRelevant,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(typeName, PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, id,
                disposition, true, true, checkpointRelevant, "test/" + typeName, scope);
    }

    /** A live packet object with nothing but its ID. */
    private record TestPacket(int packetId) {
    }
}
