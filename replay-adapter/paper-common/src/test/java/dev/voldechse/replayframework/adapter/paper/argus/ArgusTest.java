package dev.voldechse.replayframework.adapter.paper.argus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.adapter.paper.packet.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDistribution;
import dev.voldechse.replayframework.adapter.paper.packet.SceneMembership;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class ArgusTest {

    private static final Key WORLD = Key.key("minecraft:overworld");

    private final FakePlatform platform = new FakePlatform();
    private final List<Object> published = new ArrayList<>();
    private final List<BlockPosition> anchors = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final AtomicLong tick = new AtomicLong();
    private final Argus argus = new Argus(
            platform,
            new LatticeEyePlacement(8, 8),
            (sceneId, world, packet, anchor) -> {
                published.add(packet);
                anchors.add(anchor);
            },
            tick::get,
            failures::add);

    @Test
    void observesBoundedRegionsOnly() {
        assertTrue(argus.observes(scope(region(0, 0, 15, 15))));
        assertFalse(argus.observes(RecordingScope.builder().addWorld(WORLD).build()));
    }

    @Test
    void opensEyesOnTheNextTickAndDrivesThem() {
        argus.watch(scope(region(0, 0, 15, 15)));
        assertTrue(platform.eyes.isEmpty());

        argus.tick();
        argus.tick();

        assertEquals(1, platform.eyes.size());
        assertEquals(2, platform.eyes.get(0).ticks);
    }

    @Test
    void neverOpensAWatchThatWasClosedFirst() {
        argus.watch(scope(region(0, 0, 15, 15))).close();

        argus.tick();

        assertTrue(platform.eyes.isEmpty());
    }

    @Test
    void rejectsAScopeBeyondTheEyeBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> argus.watch(scope(region(0, 0, 4999, 4999))));
    }

    @Test
    void publishesSharedStateOnceAcrossOverlappingWatches() {
        argus.watch(scope(region(0, 0, 15, 15)));
        argus.watch(scope(region(8, 8, 23, 23)));
        argus.tick();
        FakeEye first = platform.eyes.get(0);
        FakeEye second = platform.eyes.get(1);
        Move move = new Move();

        first.receive(new Chunk(1, 1, true));
        second.receive(new Chunk(1, 1, true));
        first.receive(move);
        second.receive(move);
        first.receive(new Chunk(1, 1, false));
        second.receive(new Chunk(1, 1, false));

        assertEquals(
                List.of(new Chunk(1, 1, true), move, new Chunk(1, 1, false)),
                published);
        assertEquals(new BlockPosition(8, 0, 8), anchors.get(0));
        assertTrue(failures.isEmpty());
    }

    @Test
    void dropsViewLocalPacketsAndPacketsWithoutDefinition() {
        FakeEye eye = openSingleEye();

        eye.receive(new Local());
        eye.receive("not a captured packet");

        assertTrue(published.isEmpty());
    }

    @Test
    void dropsTheWholePairingBundleOfAnEntityAnotherEyeHolds() {
        argus.watch(scope(region(0, 0, 15, 15)));
        argus.watch(scope(region(8, 8, 23, 23)));
        argus.tick();
        Spawn spawn = new Spawn(7);
        Move firstData = new Move();
        Move secondData = new Move();

        platform.eyes.get(0).receive(new Bundle(List.of(spawn, firstData)));
        platform.eyes.get(1).receive(new Bundle(List.of(new Spawn(7), secondData)));

        assertEquals(List.of(spawn, firstData), published);
    }

    @Test
    void narrowsAnEntityRemovalToWhatLeftTheScene() {
        argus.watch(scope(region(0, 0, 15, 15)));
        argus.watch(scope(region(8, 8, 23, 23)));
        argus.tick();
        FakeEye first = platform.eyes.get(0);
        FakeEye second = platform.eyes.get(1);
        first.receive(new Spawn(1));
        first.receive(new Spawn(2));
        second.receive(new Spawn(2));
        published.clear();

        first.receive(new Remove(new int[] {1, 2}));

        Remove narrowed = assertInstanceOf(Remove.class, published.get(0));
        assertArrayEquals(new int[] {1}, narrowed.ids());
        assertEquals(1, published.size());
    }

    @Test
    void unloadsWhatAClosedWatchWasHoldingAlone() {
        SceneObservation.Watch closing = argus.watch(scope(region(0, 0, 15, 15)));
        argus.watch(scope(region(8, 8, 23, 23)));
        argus.tick();
        FakeEye first = platform.eyes.get(0);
        FakeEye second = platform.eyes.get(1);
        first.receive(new Chunk(0, 0, true));
        first.receive(new Chunk(1, 1, true));
        second.receive(new Chunk(1, 1, true));
        first.receive(new Spawn(5));
        published.clear();

        closing.close();
        argus.tick();

        assertTrue(first.closed);
        assertFalse(second.closed);
        assertEquals(2, published.size());
        assertEquals(new Chunk(0, 0, false), published.get(0));
        assertArrayEquals(new int[] {5}, ((Remove) published.get(1)).ids());
    }

    @Test
    void publishesSampledWorldStateOncePerWatch() {
        openSingleEye();
        platform.ambientChanges.add("rain");

        argus.tick();

        assertEquals(List.of("rain"), published);
        assertEquals(List.of(new BlockPosition(8, 0, 8)), anchors);
    }

    @Test
    void reportsAFailingPacketInsteadOfThrowingIntoTheServer() {
        FakeEye eye = openSingleEye();

        eye.receive(new Broken());

        assertEquals(1, failures.size());
        assertTrue(published.isEmpty());
    }

    @Test
    void closesEveryEyeOnShutdownWithoutPublishing() {
        FakeEye eye = openSingleEye();
        eye.receive(new Chunk(0, 0, true));
        published.clear();

        argus.close();
        eye.receive(new Move());

        assertTrue(eye.closed);
        assertTrue(published.isEmpty());
    }

    private FakeEye openSingleEye() {
        argus.watch(scope(region(0, 0, 15, 15)));
        argus.tick();
        return platform.eyes.get(0);
    }

    private static RecordingScope scope(CuboidRegion region) {
        return RecordingScope.builder().addRegion(region).build();
    }

    private static CuboidRegion region(int minX, int minZ, int maxX, int maxZ) {
        return new CuboidRegion(
                WORLD, new BlockPosition(minX, 0, minZ), new BlockPosition(maxX, 0, maxZ));
    }

    private record Chunk(int x, int z, boolean gained) {}

    private record Spawn(int id) {}

    private record Remove(int[] ids) {}

    private record Bundle(List<Object> parts) {}

    private static final class Move {}

    private static final class Local {}

    private static final class Broken {}

    private static final class FakeEye implements ArgusEye {
        private final Consumer<Object> packets;
        private int ticks;
        private boolean closed;

        private FakeEye(Consumer<Object> packets) {
            this.packets = packets;
        }

        private void receive(Object packet) {
            packets.accept(packet);
        }

        @Override
        public void tick() {
            ticks++;
        }

        @Override
        public void moveTo(double x, double y, double z) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakePlatform implements ArgusPlatform {
        private final List<FakeEye> eyes = new ArrayList<>();
        private final List<Object> ambientChanges = new ArrayList<>();

        @Override
        public ArgusEye openEye(EyeSpec spec, Consumer<Object> packets) {
            FakeEye eye = new FakeEye(packets);
            eyes.add(eye);
            return eye;
        }

        @Override
        public List<Object> unbundle(Object packet) {
            return packet instanceof Bundle bundle ? bundle.parts() : List.of(packet);
        }

        @Override
        public Optional<PacketDefinition<?>> definition(Object packet) {
            return switch (packet) {
                case Chunk chunk -> Optional.of(new Definition(
                        PacketDistribution.VIEW_MEMBERSHIP,
                        chunk.gained()
                                ? SceneMembership.chunkGained(chunk.x(), chunk.z())
                                : SceneMembership.chunkLost(chunk.x(), chunk.z())));
                case Spawn spawn -> Optional.of(new Definition(
                        PacketDistribution.VIEW_MEMBERSHIP, SceneMembership.entityGained(spawn.id())));
                case Remove remove -> Optional.of(new Definition(
                        PacketDistribution.VIEW_MEMBERSHIP, SceneMembership.entitiesLost(remove.ids())));
                case Move ignored -> Optional.of(new Definition(PacketDistribution.BROADCAST, null));
                case Local ignored -> Optional.of(new Definition(PacketDistribution.VIEW_LOCAL, null));
                case Broken ignored -> Optional.of(new Definition(
                        PacketDistribution.VIEW_MEMBERSHIP, null));
                default -> Optional.empty();
            };
        }

        @Override
        public Object forgetChunk(int chunkX, int chunkZ) {
            return new Chunk(chunkX, chunkZ, false);
        }

        @Override
        public Object removeEntities(int[] entityIds) {
            return new Remove(entityIds);
        }

        @Override
        public int trackingRangeBlocks(String world) {
            return 64;
        }

        @Override
        public AmbientSource ambient(String world) {
            return () -> {
                List<Object> changes = List.copyOf(ambientChanges);
                ambientChanges.clear();
                return changes;
            };
        }
    }

    private static final class Definition extends PacketDefinition<Object> {
        private final PacketDistribution distribution;
        private final SceneMembership membership;

        private Definition(PacketDistribution distribution, SceneMembership membership) {
            super(1, "minecraft:test", PacketClassification.stateful(PacketDescriptor.PlaybackScope.SAFE));
            this.distribution = distribution;
            this.membership = membership;
        }

        @Override
        public PacketDistribution distribution() {
            return distribution;
        }

        @Override
        public SceneMembership sceneMembership(Object decodedPacket) {
            if (membership == null) {
                throw new IllegalStateException("no membership");
            }
            return membership;
        }
    }
}
