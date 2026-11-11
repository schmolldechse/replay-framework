package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.voldechse.replayframework.adapter.PacketRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import org.junit.jupiter.api.Test;

class Paper26_3ViewResetterTest {

    @Test
    void clearsEveryActiveCrackInStableIdOrderAtItsLatestPosition() {
        List<Object> writes = new ArrayList<>();
        Paper26_3ViewResetter resetter = resetter(writes);
        BlockPos first = new BlockPos(1, 2, 3);
        BlockPos shared = new BlockPos(4, 5, 6);
        BlockPos moved = new BlockPos(7, 8, 9);

        resetter.observe(new ClientboundBlockDestructionPacket(11, first, 0));
        resetter.observe(new ClientboundBlockDestructionPacket(12, shared, 9));
        resetter.observe(new ClientboundBlockDestructionPacket(13, shared, 4));
        resetter.observe(new ClientboundBlockDestructionPacket(11, moved, 5));
        resetter.reset();

        assertEquals(3, writes.size());
        assertClearPacket(writes.get(0), 11, moved);
        assertClearPacket(writes.get(1), 12, shared);
        assertClearPacket(writes.get(2), 13, shared);

        resetter.reset();
        assertEquals(3, writes.size());
    }

    @Test
    void treatsEveryUnsignedProgressFromTenThrough255AsRemoval() {
        List<Object> writes = new ArrayList<>();
        Paper26_3ViewResetter resetter = resetter(writes);

        for (int progress = 10; progress <= 0xFF; progress++) {
            resetter.observe(new ClientboundBlockDestructionPacket(progress, BlockPos.ZERO, 0));
            resetter.observe(new ClientboundBlockDestructionPacket(
                    progress, new BlockPos(100, 64, -100), progress));
        }
        resetter.reset();

        assertEquals(List.of(), writes);
    }

    @Test
    void keepsTrackedCracksWhenTheResetWriterFails() {
        AtomicBoolean fail = new AtomicBoolean(true);
        List<Object> writes = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        RuntimeException expected = new RuntimeException("write failed");
        Paper26_3ViewResetter resetter = new Paper26_3ViewResetter(
                PacketRegistry.of(List.of()),
                packet -> {
                    if (fail.get()) {
                        throw expected;
                    }
                    writes.add(packet);
                },
                failures::add);
        BlockPos position = new BlockPos(3, 70, -4);
        resetter.observe(new ClientboundBlockDestructionPacket(91, position, 3));

        assertSame(expected, assertThrows(RuntimeException.class, resetter::reset));
        assertEquals(List.of(expected), failures);

        fail.set(false);
        resetter.reset();
        assertEquals(1, writes.size());
        assertClearPacket(writes.get(0), 91, position);
    }

    private static Paper26_3ViewResetter resetter(List<Object> writes) {
        return new Paper26_3ViewResetter(
                PacketRegistry.of(List.of()),
                writes::add,
                failure -> {
                    throw new AssertionError(failure);
                });
    }

    private static void assertClearPacket(Object value, int expectedId, BlockPos expectedPosition) {
        ClientboundBlockDestructionPacket packet =
                (ClientboundBlockDestructionPacket) value;
        assertEquals(expectedId, packet.getId());
        assertEquals(expectedPosition, packet.getPos());
        assertEquals(-1, packet.getProgress());
    }
}
