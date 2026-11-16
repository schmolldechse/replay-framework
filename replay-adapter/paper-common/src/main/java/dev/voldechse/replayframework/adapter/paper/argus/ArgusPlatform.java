package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything Argus needs from one Minecraft version.
 *
 * <p>Placement, merging and lifecycle are shared by all adapters. A new
 * adapter implements this interface and nothing else.</p>
 */
public interface ArgusPlatform {

    /**
     * Opens an eye. Every packet the server sends to it is passed to
     * {@code packets} as a native packet object on the server thread.
     */
    ArgusEye openEye(EyeSpec spec, Consumer<Object> packets);

    /** Returns the packets inside a bundle, or the packet itself. */
    List<Object> unbundle(Object packet);

    /** Returns the definition of a native packet, or empty when it is never captured. */
    Optional<PacketDefinition<?>> definition(Object packet);

    /** Builds the native packet that unloads one chunk. */
    Object forgetChunk(int chunkX, int chunkZ);

    /** Builds the native packet that removes entities. */
    Object removeEntities(int[] entityIds);

    /**
     * Returns the smallest horizontal distance, in blocks, within which the
     * entity tracker of a world pairs every entity type with a recipient.
     */
    int trackingRangeBlocks(String world);

    /** Opens the sampled time and weather source of a world. */
    AmbientSource ambient(String world);
}
