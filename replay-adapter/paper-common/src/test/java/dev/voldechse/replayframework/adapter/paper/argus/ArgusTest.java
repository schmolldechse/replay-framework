package dev.voldechse.replayframework.adapter.paper.argus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.SceneObservation;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketClassification;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.SceneMembership;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.ObserverOptions;
import java.time.Duration;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class ArgusTest {

    private static final Key WORLD = Key.key("minecraft:overworld");
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000005a40");
    private static final RecordingScope WORLD_SCOPE = RecordingScope.builder().addWorld(WORLD).build();
    private static final UUID OBSERVATION = UUID.randomUUID();
    /** Wider than one eye reaches at a tracking range of 64 blocks: two eyes. */
    private static final CuboidRegion TWO_EYES = region(0, 0, 119, 15);

    private final FakePlatform platform = new FakePlatform();
    private final List<Object> published = new ArrayList<>();
    private final List<UUID> observations = new ArrayList<>();
    private final List<UUID> closedScenes = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final AtomicLong tick = new AtomicLong();
    private final Argus argus = new Argus(
            platform,
            new ArgusPublisher() {
                @Override
                public void publish(UUID observationId, UUID sceneId, String world, Object packet) {
                    published.add(packet);
                    observations.add(observationId);
                }

                @Override
                public void publishState(
                        UUID observationId,
                        dev.voldechse.replayframework.adapter.CaptureSubject subject,
                        Object packet) {
                }

                @Override
                public void publishBlockState(UUID observationId, String world, Object packet) {
                }

                @Override
                public void sceneClosed(UUID sceneId) {
                    closedScenes.add(sceneId);
                }
            },
            tick::get,
            failures::add);

    @Test
    void observesEveryScope() {
        assertTrue(argus.observes(scope(region(0, 0, 15, 15))));
        assertTrue(argus.observes(RecordingScope.builder().addWorld(WORLD).build()));
        assertTrue(argus.observes(RecordingScope.builder().build()));
    }

    @Test
    void coversWhatItsEyesSeeBeyondTheRegion() {
        // One eye at (8, 8) with view distance 2 receives three chunks each way.
        RecordingScope coverage = argus.coverage(request(scope(region(0, 0, 15, 15)), OBSERVATION));

        CuboidRegion covered = coverage.regions().get(0);
        assertEquals(new BlockPosition(-40, -2048, -40), covered.min());
        assertEquals(new BlockPosition(56, 2047, 56), covered.max());
        assertEquals(WORLD, covered.world());
    }

    @Test
    void opensEyesOnTheNextTickAndDrivesThem() {
        argus.watch(request(scope(region(0, 0, 15, 15)), OBSERVATION));
        assertTrue(platform.eyes.isEmpty());

        argus.tick();
        argus.tick();

        assertEquals(1, platform.eyes.size());
        assertEquals(2, platform.eyes.get(0).ticks);
    }

    @Test
    void neverOpensAWatchThatWasClosedFirst() {
        argus.watch(request(scope(region(0, 0, 15, 15)), OBSERVATION)).close();

        argus.tick();

        assertTrue(platform.eyes.isEmpty());
    }

    @Test
    void rejectsAScopeBeyondTheEyeBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> argus.watch(new SceneObservation.Request(
                        OBSERVATION, scope(region(0, 0, 4999, 4999)), Set.of(),
                        ObserverOptions.builder().maxEyesPerRegion(8).build())));
        // Without a budget the same region is accepted.
        argus.watch(request(scope(region(0, 0, 4999, 4999)), OBSERVATION));
    }

    @Test
    void publishesSharedStateOnceAcrossTheEyesOfAWatch() {
        argus.watch(request(scope(TWO_EYES), OBSERVATION));
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
        assertEquals(List.of(OBSERVATION, OBSERVATION, OBSERVATION), observations);
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
        argus.watch(request(scope(TWO_EYES), OBSERVATION));
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
        argus.watch(request(scope(TWO_EYES), OBSERVATION));
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
    void keepsTheScenesOfTwoWatchesApart() {
        UUID other = UUID.randomUUID();
        argus.watch(request(scope(region(0, 0, 15, 15)), OBSERVATION));
        argus.watch(request(scope(region(8, 8, 23, 23)), other));
        argus.tick();
        Move move = new Move();

        platform.eyes.get(0).receive(new Chunk(1, 1, true));
        platform.eyes.get(1).receive(new Chunk(1, 1, true));
        platform.eyes.get(0).receive(move);
        platform.eyes.get(1).receive(move);

        assertEquals(
                List.of(new Chunk(1, 1, true), new Chunk(1, 1, true), move, move),
                published);
        assertEquals(List.of(OBSERVATION, other, OBSERVATION, other), observations);
    }

    @Test
    void unloadsEverythingAClosedWatchWasHolding() {
        SceneObservation.Watch closing = argus.watch(request(scope(region(0, 0, 15, 15)), OBSERVATION));
        argus.watch(request(scope(region(8, 8, 23, 23)), UUID.randomUUID()));
        argus.tick();
        FakeEye first = platform.eyes.get(0);
        FakeEye second = platform.eyes.get(1);
        first.receive(new Chunk(0, 0, true));
        first.receive(new Spawn(5));
        second.receive(new Chunk(0, 0, true));
        published.clear();
        observations.clear();

        closing.close();
        argus.tick();

        assertTrue(first.closed);
        assertFalse(second.closed);
        assertEquals(2, published.size());
        assertEquals(new Chunk(0, 0, false), published.get(0));
        assertArrayEquals(new int[] {5}, ((Remove) published.get(1)).ids());
        assertEquals(List.of(OBSERVATION, OBSERVATION), observations);
        assertEquals(1, closedScenes.size());
    }

    @Test
    void publishesSampledWorldStateIntoTheSceneOfItsWatch() {
        openSingleEye();
        platform.ambientChanges.add("rain");

        argus.tick();

        assertEquals(List.of("rain"), published);
        assertEquals(List.of(OBSERVATION), observations);
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

    @Test
    void followsAPlayerAndMovesWithIt() {
        platform.online(alex(OVERWORLD, 10.0, 64.0, 20.0, true));
        argus.watch(request(WORLD_SCOPE, OBSERVATION));
        step();

        FakeEye eye = platform.eyes.get(0);
        assertEquals(new EyeSpec(OVERWORLD, 10.0, 64.0, 20.0, 10), eye.spec);

        platform.online(alex(OVERWORLD, 40.0, 70.0, -5.0, true));
        step();

        assertEquals(List.of(40.0, 70.0, -5.0), eye.position);
        assertEquals(1, platform.eyes.size());
    }

    @Test
    void keepsTheEyeWhereThePlayerDiedUntilTheRespawn() {
        platform.online(alex(OVERWORLD, 10.0, 64.0, 20.0, true));
        argus.watch(request(WORLD_SCOPE, OBSERVATION));
        step();
        FakeEye eye = platform.eyes.get(0);

        platform.online(alex(OVERWORLD, 10.0, 60.0, 20.0, false));
        step();
        assertEquals(List.of(), eye.position);

        platform.online(alex(OVERWORLD, 0.0, 64.0, 0.0, true));
        step();
        assertEquals(List.of(0.0, 64.0, 0.0), eye.position);
        assertFalse(eye.closed);
    }

    @Test
    void reopensTheEyeInTheWorldThePlayerChangedTo() {
        platform.online(alex(OVERWORLD, 10.0, 64.0, 20.0, true));
        argus.watch(request(RecordingScope.builder().build(), OBSERVATION));
        step();
        FakeEye first = platform.eyes.get(0);
        first.receive(new Chunk(0, 1, true));
        published.clear();

        platform.online(alex(NETHER, 1.0, 64.0, 2.0, true));
        step();

        assertTrue(first.closed);
        assertEquals(NETHER, platform.eyes.get(1).spec.world());
        assertEquals(List.of(new Chunk(0, 1, false)), published);
    }

    @Test
    void keepsObservingForTheGracePeriodAfterThePlayerLeft() {
        platform.online(alex(OVERWORLD, 10.0, 64.0, 20.0, true));
        argus.watch(request(WORLD_SCOPE, OBSERVATION, Duration.ofSeconds(1)));
        step();
        FakeEye eye = platform.eyes.get(0);

        platform.online();
        for (int i = 0; i < 20; i++) {
            step();
            assertFalse(eye.closed, "closed after " + (i + 1) + " ticks");
        }
        step();

        assertTrue(eye.closed);
    }

    @Test
    void followsAgainWhenThePlayerReturnsWithinTheGracePeriod() {
        platform.online(alex(OVERWORLD, 10.0, 64.0, 20.0, true));
        argus.watch(request(WORLD_SCOPE, OBSERVATION, Duration.ofSeconds(1)));
        step();
        FakeEye eye = platform.eyes.get(0);

        platform.online();
        for (int i = 0; i < 10; i++) {
            step();
        }
        platform.online(alex(OVERWORLD, 12.0, 64.0, 20.0, true));
        for (int i = 0; i < 30; i++) {
            step();
        }

        assertFalse(eye.closed);
        assertEquals(1, platform.eyes.size());
        assertEquals(List.of(12.0, 64.0, 20.0), eye.position);
    }

    @Test
    void closesAtOnceWithoutAGracePeriod() {
        platform.online(alex(OVERWORLD, 10.0, 64.0, 20.0, true));
        argus.watch(request(WORLD_SCOPE, OBSERVATION, Duration.ZERO));
        step();

        platform.online();
        step();

        assertTrue(platform.eyes.get(0).closed);
    }

    @Test
    void followsOnlyTheParticipantsOfARecording() {
        platform.online(alex(OVERWORLD, 0.0, 64.0, 0.0, true), sam(OVERWORLD, 100.0, 64.0, 0.0));
        argus.watch(new SceneObservation.Request(
                OBSERVATION, WORLD_SCOPE, Set.of(SAM), ObserverOptions.defaults()));
        step();

        assertEquals(1, platform.eyes.size());
        assertEquals(100.0, platform.eyes.get(0).spec.x());
    }

    @Test
    void followsEveryPlayerUnlessABudgetIsSet() {
        platform.online(alex(OVERWORLD, 0.0, 64.0, 0.0, true), sam(OVERWORLD, 100.0, 64.0, 0.0));
        argus.watch(request(WORLD_SCOPE, OBSERVATION));
        argus.watch(new SceneObservation.Request(
                UUID.randomUUID(), WORLD_SCOPE, Set.of(),
                ObserverOptions.builder().maxCompanions(1).build()));
        step();

        assertEquals(3, platform.eyes.size());
    }

    @Test
    void followsNobodyForAScopeOfRegionsOnly() {
        platform.online(alex(OVERWORLD, 0.0, 64.0, 0.0, true));
        argus.watch(request(scope(region(0, 0, 15, 15)), OBSERVATION));
        step();

        assertEquals(1, platform.eyes.size());
        assertEquals(8.0, platform.eyes.get(0).spec.x());
    }

    @Test
    void announcesADepartureOnlyWhenNobodyElseReceivedIt() {
        platform.online(alex(OVERWORLD, 0.0, 64.0, 0.0, true), sam(OVERWORLD, 100.0, 64.0, 0.0));
        argus.watch(request(WORLD_SCOPE, OBSERVATION));
        step();
        published.clear();

        platform.online(sam(OVERWORLD, 100.0, 64.0, 0.0));
        step();
        assertTrue(published.stream().noneMatch(PlayerInfoRemove.class::isInstance));

        platform.online();
        step();
        assertEquals(
                List.of(new PlayerInfoRemove(List.of(SAM))),
                published.stream().filter(PlayerInfoRemove.class::isInstance).toList());
    }

    @Test
    void coversWhereItsCompanionsAre() {
        platform.online(alex(OVERWORLD, 0.0, 64.0, 0.0, true));
        // Argus learns who is online on its tick; the initial coverage uses that.
        step();
        assertEquals(new BlockPosition(-176, -2048, -176), argus.coverage(
                request(WORLD_SCOPE, OBSERVATION)).regions().get(0).min());
        SceneObservation.Watch watch = argus.watch(request(WORLD_SCOPE, OBSERVATION));
        step();

        platform.online(alex(OVERWORLD, 1000.0, 64.0, 0.0, true));
        step();

        CuboidRegion covered = watch.coverage().regions().get(0);
        assertEquals(new BlockPosition(824, -2048, -176), covered.min());
        assertEquals(new BlockPosition(1176, 2047, 176), covered.max());
    }

    @Test
    void coversTheRequestedScopeWhileNoEyeIsOpen() {
        SceneObservation.Watch watch = argus.watch(request(WORLD_SCOPE, OBSERVATION));
        step();

        assertEquals(WORLD_SCOPE, watch.coverage());
    }

    private void step() {
        tick.incrementAndGet();
        argus.tick();
    }

    private static PlayerAnchor alex(String world, double x, double y, double z, boolean alive) {
        return new PlayerAnchor(ALEX, world, x, y, z, alive, 10);
    }

    private static PlayerAnchor sam(String world, double x, double y, double z) {
        return new PlayerAnchor(SAM, world, x, y, z, true, 10);
    }

    private static SceneObservation.Request request(RecordingScope scope, UUID observation) {
        return new SceneObservation.Request(observation, scope, Set.of(), ObserverOptions.defaults());
    }

    private static SceneObservation.Request request(
            RecordingScope scope, UUID observation, Duration grace) {
        return new SceneObservation.Request(
                observation, scope, Set.of(), ObserverOptions.builder().gracePeriod(grace).build());
    }

    private FakeEye openSingleEye() {
        argus.watch(request(scope(region(0, 0, 15, 15)), OBSERVATION));
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

    private record PlayerInfoRemove(List<UUID> players) {}

    private static final class Move {}

    private static final class Local {}

    private static final class Broken {}

    private static final class FakeEye implements ArgusEye {
        private final Consumer<Object> packets;
        private EyeSpec spec;
        private List<Double> position = List.of();
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
            position = List.of(x, y, z);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakePlatform implements ArgusPlatform {
        private final List<FakeEye> eyes = new ArrayList<>();
        private final List<Object> ambientChanges = new ArrayList<>();
        private List<PlayerAnchor> players = List.of();

        private void online(PlayerAnchor... online) {
            players = List.of(online);
        }

        @Override
        public List<PlayerAnchor> players() {
            return players;
        }

        @Override
        public Object removePlayerInfo(List<UUID> left) {
            return new PlayerInfoRemove(left);
        }

        @Override
        public List<SubjectSample> subjects(
                String world,
                java.util.Collection<dev.voldechse.replayframework.api.recording.CuboidRegion> areas,
                SubjectSample.Cadence cadence) {
            return List.of();
        }

        @Override
        public java.util.Optional<List<BlockSample>> blockStates(String world, int chunkX, int chunkZ) {
            return java.util.Optional.of(List.of());
        }

        @Override
        public Object blockStateRemoved(dev.voldechse.replayframework.api.recording.BlockPosition position) {
            return position;
        }

        @Override
        public ArgusEye openEye(EyeSpec spec, Consumer<Object> packets) {
            FakeEye eye = new FakeEye(packets);
            eye.spec = spec;
            eyes.add(eye);
            return eye;
        }

        @Override
        public List<Object> unbundle(Object packet) {
            return packet instanceof Bundle bundle ? bundle.parts() : List.of(packet);
        }

        @Override
        public Optional<ClientboundPacketDefinition<?>> definition(Object packet) {
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

    private static final class Definition extends ClientboundPacketDefinition<Object> {
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
