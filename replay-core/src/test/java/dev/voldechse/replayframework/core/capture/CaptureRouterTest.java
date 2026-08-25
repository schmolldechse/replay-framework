package dev.voldechse.replayframework.core.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureBridge;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class CaptureRouterTest {

    private final List<Throwable> adapterFailures = new ArrayList<>();
    private final Map<RecordingSessionId, Throwable> sinkFailures = new HashMap<>();
    private CaptureRouter router;

    @BeforeEach
    void setUp() {
        PacketDescriptor stateful = new PacketDescriptor(
                "play.clientbound.test_state",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                1,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/test_state");
        PacketDescriptor control = new PacketDescriptor(
                "play.clientbound.test_control",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                2,
                PacketDisposition.CONTROL,
                false,
                false,
                false,
                "paper-26.2/test_control");
        router = new CaptureRouter(
                PacketRegistry.of(List.of(stateful, control)),
                adapterFailures::add,
                sinkFailures::put);
    }

    @Test
    void fansOutOneImmutablePacketOnlyToAcceptingSinks() {
        List<CapturedPacket> accepted = new ArrayList<>();
        List<CapturedPacket> rejected = new ArrayList<>();
        router.register(sink(RecordingSessionId.random(), true, accepted, null));
        router.register(sink(RecordingSessionId.random(), false, rejected, null));

        byte[] source = {1, 2, 3};
        router.accept(packet(1, source));
        source[0] = 9;

        assertEquals(1, accepted.size());
        assertTrue(rejected.isEmpty());
        assertArrayEquals(new byte[] {1, 2, 3}, accepted.get(0).payload());
        assertTrue(adapterFailures.isEmpty());
    }

    @Test
    void failingSinkIsRemovedAndDoesNotBlockOtherSinks() {
        RecordingSessionId failingId = RecordingSessionId.random();
        List<CapturedPacket> healthyPackets = new ArrayList<>();
        router.register(sink(failingId, true, new ArrayList<>(),
                new IllegalStateException("queue failed")));
        router.register(sink(RecordingSessionId.random(), true, healthyPackets, null));

        router.accept(packet(1, new byte[] {4}));
        router.accept(packet(1, new byte[] {5}));

        assertEquals(2, healthyPackets.size());
        assertEquals("queue failed", sinkFailures.get(failingId).getMessage());
    }

    @Test
    void duplicateSessionRegistrationIsRejected() {
        RecordingSessionId id = RecordingSessionId.random();
        router.register(sink(id, true, new ArrayList<>(), null));

        assertThrows(IllegalArgumentException.class,
                () -> router.register(sink(id, true, new ArrayList<>(), null)));
    }

    @Test
    void controlAndUnknownPacketsNeverEnterRecordingSinks() {
        List<CapturedPacket> received = new ArrayList<>();
        router.register(sink(RecordingSessionId.random(), true, received, null));

        router.accept(packet(2, new byte[] {6}));
        router.accept(packet(99, new byte[] {7}));

        assertTrue(received.isEmpty());
        assertEquals(1, adapterFailures.size());
    }

    @Test
    void closedRouterRejectsNewRegistrationAndDoesNotEnqueue() {
        List<CapturedPacket> received = new ArrayList<>();
        router.close();

        assertThrows(IllegalStateException.class,
                () -> router.register(sink(RecordingSessionId.random(), true, received, null)));
        router.accept(packet(1, new byte[] {8}));
        assertTrue(received.isEmpty());
    }

    private static CaptureSink sink(
            RecordingSessionId id,
            boolean accepts,
            List<CapturedPacket> received,
            RuntimeException failure) {
        return new CaptureSink() {
            @Override
            public RecordingSessionId sessionId() {
                return id;
            }

            @Override
            public boolean accepts(CapturedPacket packet) {
                return accepts;
            }

            @Override
            public void enqueue(CapturedPacket packet) {
                if (failure != null) {
                    throw failure;
                }
                received.add(packet);
            }
        };
    }

    private static CaptureBridge.CapturePacket packet(int packetId, byte[] payload) {
        return new CaptureBridge.CapturePacket(
                UUID.randomUUID(),
                100L,
                20L,
                0,
                PacketPhase.PLAY,
                packetId,
                payload);
    }
}
