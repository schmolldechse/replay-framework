package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.capture.PaperConnectionAccessor;
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
            "paper-26.2/minecraft:test_replayable");

    private static final PacketDescriptor CONTROL = new PacketDescriptor(
            "minecraft:test_control",
            PacketPhase.PLAY,
            PacketDescriptor.Direction.CLIENTBOUND,
            8,
            PacketDisposition.CONTROL,
            false,
            false,
            false,
            "paper-26.2/minecraft:test_control");

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
    void forwardsControlPacketsAndWritesReplayThroughTheBypass() {
        PacketRegistry registry = PacketRegistry.of(List.of(REPLAYABLE, CONTROL));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ReplayViewerOutboundGate gate = gateFor(registry, failure);
        EmbeddedChannel channel = new EmbeddedChannel(gate);

        TestPacket controlPacket = new TestPacket(8);
        assertTrue(channel.writeOutbound(controlPacket));
        assertSame(controlPacket, channel.readOutbound());

        TestPacket replayPacket = new TestPacket(7);
        gate.writeReplay(replayPacket);
        channel.runPendingTasks();
        channel.flushOutbound();
        assertSame(replayPacket, channel.readOutbound());
        assertNull(failure.get());
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
