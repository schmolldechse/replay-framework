package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.voldechse.replayframework.adapter.PacketRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.world.BossEvent;
import org.junit.jupiter.api.Test;

class Paper26_3ViewResetterTest {

    @Test
    void removesOnlyActiveBossBarsInStableAddOrderAfterEveryUpdateVariant() {
        List<Object> writes = new ArrayList<>();
        Paper26_3ViewResetter resetter = resetter(writes);
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID removed = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000013");
        TestBossEvent firstEvent = bossEvent(first);
        TestBossEvent removedEvent = bossEvent(removed);

        resetter.observe(ClientboundBossEventPacket.createAddPacket(firstEvent));
        resetter.observe(ClientboundBossEventPacket.createUpdateProgressPacket(firstEvent));
        resetter.observe(ClientboundBossEventPacket.createUpdateNamePacket(firstEvent));
        resetter.observe(ClientboundBossEventPacket.createUpdateStylePacket(firstEvent));
        resetter.observe(ClientboundBossEventPacket.createUpdatePropertiesPacket(firstEvent));
        resetter.observe(ClientboundBossEventPacket.createAddPacket(removedEvent));
        resetter.observe(ClientboundBossEventPacket.createAddPacket(bossEvent(third)));
        resetter.observe(ClientboundBossEventPacket.createRemovePacket(removed));
        resetter.reset();

        assertEquals(2, writes.size());
        assertBossBarRemoval(writes.get(0), first);
        assertBossBarRemoval(writes.get(1), third);

        resetter.reset();
        assertEquals(2, writes.size());
    }

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

    private static void assertBossBarRemoval(Object value, UUID expectedId) {
        ClientboundBossEventPacket packet = (ClientboundBossEventPacket) value;
        AtomicReference<UUID> removed = new AtomicReference<>();
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void remove(UUID id) {
                removed.set(id);
            }
        });
        assertEquals(expectedId, removed.get());
    }

    private static TestBossEvent bossEvent(UUID id) {
        TestBossEvent event = new TestBossEvent(id);
        event.setProgress(0.5F);
        event.setDarkenScreen(true);
        event.setPlayBossMusic(true);
        event.setCreateWorldFog(true);
        return event;
    }

    private static final class TestBossEvent extends BossEvent {
        private TestBossEvent(UUID id) {
            super(
                    id,
                    Component.literal("Replay boss"),
                    BossBarColor.PURPLE,
                    BossBarOverlay.NOTCHED_10);
        }
    }
}
