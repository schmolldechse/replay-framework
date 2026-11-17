package dev.voldechse.replayframework.core.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.RecipientCopy;
import dev.voldechse.replayframework.core.capture.CapturedPacket;
import dev.voldechse.replayframework.format.PacketPhase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class RecipientCopyMergerTest {
    private static final UUID ALEX = UUID.randomUUID();
    private static final UUID STEVE = UUID.randomUUID();
    private static final Key OVERWORLD = Key.key("minecraft:overworld");

    private final RecipientCopyMerger merger = new RecipientCopyMerger();

    @Test
    void keepsOneEventThatSeveralRecipientsReceived() {
        assertTrue(merger.record(copy(20L, ALEX, "boom")));
        assertFalse(merger.record(copy(20L, STEVE, "boom")));
    }

    @Test
    void keepsAsManyEventsAsOneRecipientReceived() {
        assertTrue(merger.record(copy(20L, ALEX, "note")));
        assertTrue(merger.record(copy(20L, ALEX, "note")));
        assertFalse(merger.record(copy(20L, STEVE, "note")));
        assertFalse(merger.record(copy(20L, STEVE, "note")));
        assertTrue(merger.record(copy(20L, STEVE, "note")));
    }

    @Test
    void mergesACopyCapturedOneTickLater() {
        assertTrue(merger.record(copy(20L, ALEX, "boom")));
        assertFalse(merger.record(copy(21L, STEVE, "boom")));
    }

    @Test
    void closesAnEventAfterTheFollowingTick() {
        assertTrue(merger.record(copy(20L, ALEX, "boom")));
        assertTrue(merger.record(copy(22L, STEVE, "boom")));
        assertEquals(1, merger.openEvents());
    }

    @Test
    void separatesEventsByIdentityPacketAndWorld() {
        assertTrue(merger.record(copy(20L, ALEX, "boom")));
        assertTrue(merger.record(copy(20L, STEVE, "bang")));
        assertTrue(merger.record(packet(20L, 9, OVERWORLD, Optional.of(new RecipientCopy(STEVE, "boom")))));
        assertTrue(merger.record(packet(20L, 7, Key.key("minecraft:the_nether"),
                Optional.of(new RecipientCopy(STEVE, "boom")))));
    }

    @Test
    void recordsEveryPacketThatIsNoRecipientCopy() {
        for (int repeat = 0; repeat < 3; repeat++) {
            assertTrue(merger.record(packet(20L, 7, OVERWORLD, Optional.empty())));
        }
        assertEquals(0, merger.openEvents());
    }

    @Test
    void listIdentitiesCompareByValue() {
        assertTrue(merger.record(copy(20L, ALEX, List.of(1, "explosion"))));
        assertFalse(merger.record(copy(20L, STEVE, List.of(1, "explosion"))));
    }

    private static CapturedPacket copy(long tick, UUID recipient, Object identity) {
        return packet(tick, 7, OVERWORLD, Optional.of(new RecipientCopy(recipient, identity)));
    }

    private static CapturedPacket packet(
            long tick, int packetId, Key world, Optional<RecipientCopy> copy) {
        CaptureContext context = new CaptureContext(
                Optional.empty(), Optional.of(world), Optional.empty(), Optional.empty(), Optional.empty());
        if (copy.isPresent()) {
            context = context.asRecipientCopy(copy.get());
        }
        return new CapturedPacket(0L, tick, 0, PacketPhase.PLAY, packetId, new byte[] {1}, context);
    }
}
