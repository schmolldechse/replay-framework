package dev.voldechse.replayframework.core.recording;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class RecordingScopeFilterTest {

    private static final Key OVERWORLD = Key.key("minecraft:overworld");
    private static final Key NETHER = Key.key("minecraft:the_nether");
    private static final RecordingScope ARENA = RecordingScope.builder()
            .addWorld(OVERWORLD)
            .addRegion(new CuboidRegion(
                    OVERWORLD, new BlockPosition(0, 60, 0), new BlockPosition(63, 100, 63)))
            .build();

    @Test
    void anObservedRecordingTakesItsSceneFromObservers() {
        assertTrue(RecordingRuntimeModule.acceptsOrigin(true, packet(observed())));
        assertTrue(RecordingRuntimeModule.acceptsOrigin(true, packet(context(OVERWORLD, null))));
    }

    @Test
    void aRecordingWithoutObserversTakesOnlyPlayerOnlyPackets() {
        assertFalse(RecordingRuntimeModule.acceptsOrigin(false, packet(observed())));
        assertTrue(RecordingRuntimeModule.acceptsOrigin(false, packet(context(OVERWORLD, null))));
    }

    @Test
    void aRegionAcceptsLocatedPacketsInsideItOnly() {
        assertTrue(RecordingRuntimeModule.acceptsScope(
                ARENA, packet(context(OVERWORLD, new BlockPosition(10, 64, 10)))));
        assertFalse(RecordingRuntimeModule.acceptsScope(
                ARENA, packet(context(OVERWORLD, new BlockPosition(200, 64, 10)))));
        assertFalse(RecordingRuntimeModule.acceptsScope(
                ARENA, packet(context(OVERWORLD, null))));
    }

    @Test
    void aRegionAcceptsWorldWidePacketsOfItsWorldWithoutPosition() {
        assertTrue(RecordingRuntimeModule.acceptsScope(
                ARENA, packet(context(OVERWORLD, null).asWorldWide())));
        assertTrue(RecordingRuntimeModule.acceptsScope(
                ARENA, packet(context(OVERWORLD, new BlockPosition(200, 64, 10)).asWorldWide())));
        assertFalse(RecordingRuntimeModule.acceptsScope(
                ARENA, packet(context(NETHER, null).asWorldWide())));
    }

    @Test
    void aRegionAcceptsEveryPacketOfItsObserversWhereverItHappened() {
        java.util.UUID observation = java.util.UUID.randomUUID();

        assertTrue(RecordingRuntimeModule.acceptsScope(ARENA, packet(
                context(OVERWORLD, new BlockPosition(200, 300, 10)).observedBy(observation))));
        assertTrue(RecordingRuntimeModule.acceptsScope(ARENA, packet(
                context(OVERWORLD, null).observedBy(observation))));
    }

    private static CaptureContext context(Key world, BlockPosition position) {
        return new CaptureContext(
                PacketDisposition.STATEFUL,
                Optional.of(world),
                Optional.ofNullable(position),
                Optional.empty(),
                Optional.empty());
    }

    private static CaptureContext observed() {
        return context(OVERWORLD, null).observedBy(java.util.UUID.randomUUID());
    }

    private static CapturedPacket packet(CaptureContext context) {
        return new CapturedPacket(0L, 0L, 0, PacketPhase.PLAY, 1, new byte[0], context);
    }
}
