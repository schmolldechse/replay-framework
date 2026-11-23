package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    Optional<ClientboundPacketDefinition<?>> definition(Object packet);

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

    /** Returns every online player, read on the server thread. */
    List<PlayerAnchor> players();

    /**
     * Builds the native packet that removes players from the tab list. The
     * server sends it only to the players that stay online.
     */
    Object removePlayerInfo(List<UUID> players);

    /**
     * Looks at the subjects with state of their own inside the areas of one
     * world that are due at a cadence: its players every tick, and at the
     * sampling interval the entities whose state no client receives without
     * opening them, such as the inventory of a llama.
     */
    List<SubjectSample> subjects(String world, Collection<CuboidRegion> areas, SubjectSample.Cadence cadence);

    /**
     * Looks at the blocks of one chunk with state the scene does not show,
     * such as chests and their contents. Empty when the chunk is not loaded;
     * a chunk is never loaded for this.
     */
    Optional<List<BlockSample>> blockStates(String world, int chunkX, int chunkZ);

    /** Builds the native packet that records that a block with state is gone. */
    Object blockStateRemoved(dev.voldechse.replayframework.api.recording.BlockPosition position);
}
