package dev.voldechse.replayframework.example.resourcepack;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Stable mapping between Example UI assets and their resource-pack keys.
 */
public enum ExamplePackAsset {
    /** Playback start icon. */
    PLAY("play", "replay_example:play", "assets/replay_example/textures/item/play.png", 0xE100),
    /** Playback pause icon. */
    PAUSE("pause", "replay_example:pause", "assets/replay_example/textures/item/pause.png", 0xE101),
    /** Playback restart icon. */
    RESTART("restart", "replay_example:restart", "assets/replay_example/textures/item/restart.png", 0xE102),
    /** Rewind icon. */
    REWIND("rewind", "replay_example:rewind", "assets/replay_example/textures/item/rewind.png", 0xE103),
    /** Forward icon. */
    FORWARD("forward", "replay_example:forward", "assets/replay_example/textures/item/forward.png", 0xE104),
    /** 0.25x speed icon. */
    SPEED_0_25("speed_0_25", "replay_example:speed_0_25", "assets/replay_example/textures/item/speed_0_25.png", 0xE105),
    /** 0.5x speed icon. */
    SPEED_0_5("speed_0_5", "replay_example:speed_0_5", "assets/replay_example/textures/item/speed_0_5.png", 0xE106),
    /** 1x speed icon. */
    SPEED_1("speed_1", "replay_example:speed_1", "assets/replay_example/textures/item/speed_1.png", 0xE107),
    /** 2x speed icon. */
    SPEED_2("speed_2", "replay_example:speed_2", "assets/replay_example/textures/item/speed_2.png", 0xE108),
    /** 4x speed icon. */
    SPEED_4("speed_4", "replay_example:speed_4", "assets/replay_example/textures/item/speed_4.png", 0xE109),
    /** Leave-viewer icon. */
    LEAVE("leave", "replay_example:leave", "assets/replay_example/textures/item/leave.png", 0xE10A),
    /** Selection frame glyph. */
    SELECTION_FRAME("selection_frame", null, null, 0xE10B),
    /** Timeline background glyph. */
    TIMELINE_BACKGROUND("timeline_background", null, null, 0xE10C),
    /** Timeline progress glyph. */
    TIMELINE_PROGRESS("timeline_progress", null, null, 0xE10D),
    /** Buffer indicator glyph. */
    BUFFER_INDICATOR("buffer_indicator", null, null, 0xE10E);

    static {
        Set<Integer> codePoints = new HashSet<>();
        for (ExamplePackAsset asset : values()) {
            if (!codePoints.add(asset.glyphCodePoint)) {
                throw new ExceptionInInitializerError("Duplicate Example Pack glyph codepoint");
            }
            if (asset.itemModelKey != null && !asset.itemModelKey.startsWith("replay_example:")) {
                throw new ExceptionInInitializerError("Example item model key must use replay_example namespace");
            }
            if (asset.itemTexturePath != null && !asset.itemTexturePath.startsWith("assets/replay_example/")) {
                throw new ExceptionInInitializerError("Example texture path must use replay_example namespace");
            }
        }
        if (codePoints.size() != 15) {
            throw new ExceptionInInitializerError("Example Pack must define exactly 15 glyphs");
        }
    }

    private final String assetId;
    private final String itemModelKey;
    private final String itemTexturePath;
    private final int glyphCodePoint;

    ExamplePackAsset(String assetId, String itemModelKey, String itemTexturePath, int glyphCodePoint) {
        this.assetId = assetId;
        this.itemModelKey = itemModelKey;
        this.itemTexturePath = itemTexturePath;
        this.glyphCodePoint = glyphCodePoint;
    }

    /**
     * Returns the stable logical asset identifier.
     *
     * @return namespaced-independent asset identifier
     */
    public String assetId() {
        return assetId;
    }

    /**
     * Returns the Minecraft item model key when this asset is an item icon.
     *
     * @return optional namespaced item model key
     */
    public Optional<String> itemModelKey() {
        return Optional.ofNullable(itemModelKey);
    }

    /**
     * Returns the classpath texture path when this asset is an item icon.
     *
     * @return optional resource-pack texture path
     */
    public Optional<String> itemTexturePath() {
        return Optional.ofNullable(itemTexturePath);
    }

    /**
     * Returns the private-use Unicode codepoint for this asset.
     *
     * @return glyph codepoint
     */
    public int glyphCodePoint() {
        return glyphCodePoint;
    }

    /**
     * Returns a one-codepoint Java string suitable for Adventure components.
     *
     * @return text containing this asset's glyph
     */
    public String glyphText() {
        return new String(Character.toChars(glyphCodePoint));
    }
}
