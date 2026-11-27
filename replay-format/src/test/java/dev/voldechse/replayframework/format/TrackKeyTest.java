package dev.voldechse.replayframework.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules every track kind gets from its key and role. */
class TrackKeyTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final TrackSubject ALEX = TrackSubject.player(UUID.randomUUID());

    @Test
    void everyKindIsKeyedAndPlayedAsDeclared() {
        assertEquals(TrackKind.KeyedBy.NOTHING, TrackKind.GLOBAL.keyedBy());
        assertEquals(TrackKind.KeyedBy.WORLD, TrackKind.WORLD.keyedBy());
        assertEquals(TrackKind.KeyedBy.SUBJECT, TrackKind.STATE.keyedBy());
        assertEquals(TrackKind.KeyedBy.WORLD, TrackKind.BLOCK_STATE.keyedBy());
        assertEquals(TrackKind.Role.SCENE, TrackKind.GLOBAL.role());
        assertEquals(TrackKind.Role.SCENE, TrackKind.WORLD.role());
        assertEquals(TrackKind.Role.STATE, TrackKind.STATE.role());
        assertEquals(TrackKind.Role.STATE, TrackKind.BLOCK_STATE.role());
    }

    @Test
    void aKeyMustMatchWhatItsKindIsKeyedBy() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackKey(TrackKind.GLOBAL, Optional.of(OVERWORLD), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackKey(TrackKind.WORLD, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackKey(TrackKind.BLOCK_STATE, Optional.of(OVERWORLD), Optional.of(ALEX)));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackKey(TrackKind.STATE, Optional.of(OVERWORLD), Optional.of(ALEX)));
        assertThrows(IllegalArgumentException.class,
                () -> TrackKey.world(" "));
    }

    @Test
    void aDescriptorHasTheKeyItWasDescribedFrom() {
        for (TrackKey key : List.of(
                TrackKey.global(), TrackKey.world(OVERWORLD), TrackKey.state(ALEX), TrackKey.blockState(OVERWORLD))) {
            assertEquals(key, key.describe(3, "label").key());
        }
        assertEquals(TrackKey.global(), TrackKey.scene(Optional.empty()));
        assertEquals(TrackKey.world(OVERWORLD), TrackKey.scene(Optional.of(OVERWORLD)));
    }

    @Test
    void aManifestHasOneTrackPerKey() {
        assertDuplicate(TrackDescriptor.global(0), TrackDescriptor.global(1));
        assertDuplicate(TrackDescriptor.global(0), TrackDescriptor.blockState(1, OVERWORLD),
                TrackDescriptor.blockState(2, OVERWORLD));
        assertDuplicate(TrackDescriptor.global(0), TrackDescriptor.state(1, ALEX, "Alex"),
                TrackDescriptor.state(2, ALEX, "Alex again"));

        assertEquals(4, manifest(TrackDescriptor.global(0), TrackDescriptor.world(1, OVERWORLD),
                TrackDescriptor.blockState(2, OVERWORLD), TrackDescriptor.state(3, ALEX, "Alex")).tracks().size());
    }

    private static void assertDuplicate(TrackDescriptor... tracks) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> manifest(tracks));
        assertTrue(failure.getMessage().startsWith("duplicate track:"), failure.getMessage());
    }

    /** A manifest with the tracks and one index per track. */
    private static ReplayManifest manifest(TrackDescriptor... tracks) {
        List<ReplayManifest.ArtifactFile> indexes = new java.util.ArrayList<>();
        for (TrackDescriptor track : tracks) {
            indexes.add(new ReplayManifest.ArtifactFile(
                    ReplayManifest.ArtifactType.INDEX, track.directory() + "/index.bin", 18L, "0".repeat(64)));
        }
        return new ReplayManifest(
                "123e4567-e89b-12d3-a456-426614174000", "paper-26.2", 775, "registry",
                ReplayManifest.CURRENT_FORMAT_REVISION, 0L, List.of(tracks), indexes);
    }
}
