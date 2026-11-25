package dev.voldechse.replayframework.adapter.paper.playback.scene;

import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Turns one replayed packet into the scene changes it describes,
 * implemented once per Minecraft version.
 */
@FunctionalInterface
public interface SceneDecoder {

    /**
     * The changes a frame describes, in order; empty when it changes nothing
     * the scene keeps.
     *
     * @param frame the frame, of any world
     * @param typeName the frame's descriptor type name
     * @param knownEntity whether the scene knows an entity ID; a decoder
     *                    should not decode packets of unknown entities
     */
    List<SceneChange> decode(RawPacketFrame frame, String typeName, IntPredicate knownEntity);
}
