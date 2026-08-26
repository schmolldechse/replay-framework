package dev.voldechse.replayframework.example.resourcepack;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamplePackAssetTest {

    @Test
    void definesStableItemKeysAndUiGlyphsWithoutGaps() {
        assertEquals(15, ExamplePackAsset.values().length);
        assertEquals(
                IntStream.rangeClosed(0xE100, 0xE10E).boxed().toList(),
                Arrays.stream(ExamplePackAsset.values())
                        .mapToInt(ExamplePackAsset::glyphCodePoint)
                        .boxed()
                        .toList());

        assertEquals(
                "replay_example:item/speed_0_25",
                ExamplePackAsset.SPEED_0_25.itemModelKey().orElseThrow());
        assertEquals(
                "assets/replay_example/textures/item/speed_0_25.png",
                ExamplePackAsset.SPEED_0_25.itemTexturePath().orElseThrow());
        assertFalse(ExamplePackAsset.BUFFER_INDICATOR.itemModelKey().isPresent());
        assertFalse(ExamplePackAsset.BUFFER_INDICATOR.itemTexturePath().isPresent());
    }

    @Test
    void exposesEachGlyphAsExactlyOneCodePoint() {
        for (ExamplePackAsset asset : ExamplePackAsset.values()) {
            assertEquals(1, asset.glyphText().codePointCount(0, asset.glyphText().length()));
            assertTrue(asset.glyphText().codePointAt(0) == asset.glyphCodePoint());
        }
    }
}
