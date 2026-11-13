package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.PaperConnectionAccessor;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import org.junit.jupiter.api.Test;

class ReplayViewerOutboundGateTest {

    private static final PacketDescriptor REPLAYABLE = new PacketDescriptor(
            "minecraft:test_replayable",
            PacketPhase.PLAY,
            PacketDescriptor.Direction.CLIENTBOUND,
            7,
            PacketDisposition.STATEFUL,
            true,
            true,
            true,
            "paper-26.3/minecraft:test_replayable");

    private static final PacketDescriptor CONTROL = new PacketDescriptor(
            "minecraft:test_control",
            PacketPhase.PLAY,
            PacketDescriptor.Direction.CLIENTBOUND,
            8,
            PacketDisposition.CONTROL,
            false,
            false,
            false,
            "paper-26.3/minecraft:test_control");

    @Test
    void suppressesReplayableLivePacketsForThisChannel() {
        PacketRegistry registry = PacketRegistry.of(List.of(REPLAYABLE));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ReplayViewerOutboundGate gate = gateFor(registry, failure);
        EmbeddedChannel channel = new EmbeddedChannel(gate);

        TestPacket livePacket = new TestPacket(7);

        assertFalse(channel.writeOutbound(livePacket));
        assertTrue(channel.outboundMessages().isEmpty());
        assertTrue(channel.isOpen());
        assertNull(failure.get());
    }

    @Test
    void forwardsTeleportTrafficUntilReplayIsolationIsActivated() {
        PacketRegistry registry = PacketRegistry.of(List.of(REPLAYABLE));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ReplayViewerOutboundGate gate = new ReplayViewerOutboundGate(
                registry,
                message -> new PaperConnectionAccessor.WirePacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        ((TestPacket) message).packetId(),
                        new byte[] {7}),
                false,
                failure::set);
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
    void forwardsControlPackets() {
        PacketRegistry registry = PacketRegistry.of(List.of(REPLAYABLE, CONTROL));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ReplayViewerOutboundGate gate = gateFor(registry, failure);
        EmbeddedChannel channel = new EmbeddedChannel(gate);

        TestPacket controlPacket = new TestPacket(8);
        assertTrue(channel.writeOutbound(controlPacket));
        assertSame(controlPacket, channel.readOutbound());

        assertNull(failure.get());
    }

    @Test
    void liveObserverStateAlwaysPassesTheReplayGate() {
        for (String name : List.of(
                "set_health",
                "player_position",
                "player_abilities",
                "set_player_inventory",
                "set_title_text",
                "clear_titles")) {
            var descriptor = new PacketDescriptor("play.clientbound.minecraft:" + name,
                    PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, 9,
                    PacketDisposition.STATEFUL, true, true, false, "test/" + name,
                    PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
            var failure = new AtomicReference<Throwable>();
            var channel = new EmbeddedChannel(gateFor(PacketRegistry.of(List.of(descriptor)), failure));
            try {
                var livePacket = new TestPacket(9);
                assertTrue(channel.writeOutbound(livePacket), name);
                assertSame(livePacket, channel.readOutbound(), name);
                assertNull(failure.get());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    private static ReplayViewerOutboundGate gateFor(
            PacketRegistry registry,
            AtomicReference<Throwable> failure) {
        return new ReplayViewerOutboundGate(
                registry,
                message -> new PaperConnectionAccessor.WirePacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        ((TestPacket) message).packetId(),
                        new byte[] {7}),
                failure::set);
    }

    private static final class TestPacket implements Packet<PacketListener> {

        private final int packetId;

        private TestPacket(int packetId) {
            this.packetId = packetId;
        }

        private int packetId() {
            return packetId;
        }

        @Override
        public PacketType<? extends Packet<PacketListener>> type() {
            return null;
        }

        @Override
        public void handle(PacketListener listener) {
            // Test packet has no protocol behavior.
        }
    }
}
