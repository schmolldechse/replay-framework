package dev.voldechse.replayframework.adapter.paper.playback.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** The version-independent rules of the scene, fed with changes directly. */
class SceneModelTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Key PLAYER = Key.key("minecraft:player");
    private static final Key ZOMBIE = Key.key("minecraft:zombie");

    private final SceneModel scene = new SceneModel((frame, typeName, known) -> List.of());

    @Test
    void followsSpawnMovementTeleportAndRemoval() {
        scene.apply(new SceneChange.PlayerName(ALEX, "Alex"));
        scene.apply(spawn(7, ALEX, PLAYER, NETHER, 10, 64, 20));

        assertEquals(location(NETHER, 10, 64, 20, 0, 0), alexLocation());

        scene.apply(new SceneChange.Move(7, 1, -0.5, 2, Optional.of(new SceneChange.Rotation(45, 5))));
        assertEquals(location(NETHER, 11, 63.5, 22, 45, 5), alexLocation());

        scene.apply(new SceneChange.Teleport(7, new SceneChange.Position(-5, 70, 3, 90, 10), Set.of()));
        assertEquals(location(NETHER, -5, 70, 3, 90, 10), alexLocation());

        scene.apply(new SceneChange.Removed(List.of(7)));
        RecordedPlayer removed = scene.players().getFirst();
        assertEquals("Alex", removed.name());
        assertFalse(removed.inScene());
    }

    @Test
    void aRelativeTeleportAddsOnlyItsRelativeAxes() {
        scene.apply(spawn(7, ALEX, PLAYER, OVERWORLD, 10, 64, 20));

        scene.apply(new SceneChange.Teleport(7, new SceneChange.Position(2, 100, -1, 30, 0),
                Set.of(SceneChange.Axis.X, SceneChange.Axis.Z, SceneChange.Axis.YAW)));

        assertEquals(location(OVERWORLD, 12, 100, 19, 30, 0), alexLocation());
    }

    @Test
    void changesOfUnknownEntitiesAreIgnored() {
        scene.apply(new SceneChange.Move(9, 1, 1, 1, Optional.empty()));
        scene.apply(new SceneChange.Health(9, 3));

        assertTrue(scene.entity(9).isEmpty());
        assertTrue(scene.players().isEmpty());
    }

    @Test
    void listsPlayersByNameWithoutOtherEntities() {
        scene.apply(new SceneChange.PlayerName(STEVE, "steve"));
        scene.apply(new SceneChange.PlayerName(ALEX, "Alex"));
        scene.apply(spawn(3, UUID.randomUUID(), ZOMBIE, OVERWORLD, 0, 0, 0));
        UUID unnamed = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        scene.apply(spawn(4, unnamed, PLAYER, OVERWORLD, 0, 0, 0));

        List<RecordedPlayer> players = scene.players();
        assertEquals(List.of(unnamed.toString(), "Alex", "steve"),
                players.stream().map(RecordedPlayer::name).toList());
        assertEquals(List.of(true, false, false), players.stream().map(RecordedPlayer::inScene).toList());
    }

    @Test
    void showsNameHealthFlagsAndTheEntitiesOfAWorld() {
        scene.apply(spawn(7, ALEX, PLAYER, OVERWORLD, 0, 64, 0));
        scene.apply(spawn(3, UUID.randomUUID(), ZOMBIE, NETHER, 0, 0, 0));
        scene.apply(new SceneChange.Flags(7, (byte) ((1 << 1) | (1 << 6))));
        scene.apply(new SceneChange.Health(7, 12.5));
        scene.apply(new SceneChange.CustomName(7, Optional.of(Component.text("Alex"))));

        RecordedEntity alex = scene.entity(7).orElseThrow();
        assertEquals(ALEX, alex.id());
        assertEquals(PLAYER, alex.type());
        assertEquals(OptionalDouble.of(12.5), alex.health());
        assertEquals(Optional.of(Component.text("Alex")), alex.customName());
        assertEquals(Set.of(RecordedEntity.Flag.SNEAKING, RecordedEntity.Flag.GLOWING), alex.flags());
        assertEquals(List.of(ALEX), scene.entities(OVERWORLD).stream().map(RecordedEntity::id).toList());
        assertEquals(1, scene.entities(NETHER).size());

        scene.apply(new SceneChange.CustomName(7, Optional.empty()));
        assertEquals(Optional.empty(), scene.entity(7).orElseThrow().customName());
    }

    @Test
    void maxHealthAddsThenMultipliesTheBaseThenTheTotal() {
        scene.apply(spawn(7, ALEX, PLAYER, OVERWORLD, 0, 0, 0));
        scene.apply(new SceneChange.MaxHealth(7, 20, List.of(
                new SceneChange.Modifier(SceneChange.Modifier.Operation.MULTIPLY_TOTAL, 0.5),
                new SceneChange.Modifier(SceneChange.Modifier.Operation.MULTIPLY_BASE, 0.5),
                new SceneChange.Modifier(SceneChange.Modifier.Operation.ADD, 4))));

        // (20 + 4) = 24; + 24 * 0.5 = 36; * 1.5 = 54
        assertEquals(OptionalDouble.of(54), scene.entity(7).orElseThrow().maxHealth());
    }

    @Test
    void followsContainersByChunkAndBlock() {
        BlockPosition chest = new BlockPosition(4, 64, 9);
        BlockPosition barrel = new BlockPosition(20, 64, 9);
        scene.apply(new SceneChange.ChunkContainers(OVERWORLD, 0, 0, Map.of(chest, Key.key("minecraft:chest"))));
        scene.apply(new SceneChange.BlockChanged(OVERWORLD, barrel, Optional.of(Key.key("minecraft:barrel"))));

        assertEquals(Optional.of(Key.key("minecraft:chest")), scene.container(OVERWORLD, chest));
        assertEquals(Optional.empty(), scene.container(NETHER, chest));

        scene.apply(new SceneChange.ChunkForgotten(OVERWORLD, 0, 0));
        assertEquals(Optional.empty(), scene.container(OVERWORLD, chest));
        assertEquals(Optional.of(Key.key("minecraft:barrel")), scene.container(OVERWORLD, barrel));

        scene.apply(new SceneChange.BlockChanged(OVERWORLD, barrel, Optional.empty()));
        assertEquals(Optional.empty(), scene.container(OVERWORLD, barrel));
    }

    @Test
    void aResentChunkReplacesItsContainers() {
        BlockPosition chest = new BlockPosition(-1, 64, -1);
        scene.apply(new SceneChange.ChunkContainers(OVERWORLD, -1, -1, Map.of(chest, Key.key("minecraft:chest"))));
        scene.apply(new SceneChange.ChunkContainers(OVERWORLD, -1, -1, Map.of()));

        assertEquals(Optional.empty(), scene.container(OVERWORLD, chest));
    }

    @Test
    void theDecoderLearnsWhichEntitiesAreKnown() {
        List<Boolean> asked = new ArrayList<>();
        SceneModel decoded = new SceneModel((frame, typeName, known) -> {
            asked.add(known.test(7));
            return typeName.equals("spawn") ? List.of(spawn(7, ALEX, PLAYER, OVERWORLD, 0, 0, 0)) : List.of();
        });
        RawPacketFrame frame = new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 1, new byte[0]);

        decoded.observe(frame, "other");
        decoded.observe(frame, "spawn");
        decoded.observe(frame, "other");

        assertEquals(List.of(false, false, true), asked);
    }

    @Test
    void clearForgetsEverything() {
        scene.apply(new SceneChange.PlayerName(ALEX, "Alex"));
        scene.apply(spawn(7, ALEX, PLAYER, OVERWORLD, 0, 0, 0));
        BlockPosition chest = new BlockPosition(4, 64, 9);
        scene.apply(new SceneChange.BlockChanged(OVERWORLD, chest, Optional.of(Key.key("minecraft:chest"))));

        scene.clear();

        assertTrue(scene.players().isEmpty());
        assertTrue(scene.entity(7).isEmpty());
        assertEquals(Optional.empty(), scene.container(OVERWORLD, chest));
    }

    private RecordedPlayer.Location alexLocation() {
        return scene.players().getFirst().location().orElseThrow();
    }

    private static SceneChange.Spawn spawn(int entityId, UUID id, Key type, String world, double x, double y, double z) {
        return new SceneChange.Spawn(entityId, id, type, world, new SceneChange.Position(x, y, z, 0, 0));
    }

    private static RecordedPlayer.Location location(String world, double x, double y, double z, float yaw, float pitch) {
        return new RecordedPlayer.Location(Key.key(world), x, y, z, yaw, pitch);
    }
}
