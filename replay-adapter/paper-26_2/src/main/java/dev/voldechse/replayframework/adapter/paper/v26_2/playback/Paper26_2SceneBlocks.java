package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.paper.v26_2.argus.Paper26_2BlockStates;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Knows where the scene shows a block whose contents the recording keeps,
 * such as a chest, so a viewer's click on it can be recognized.
 *
 * <p>Chunk packets list every block entity of a chunk with its type; block
 * updates add and remove single blocks. Blocks are kept per recorded world,
 * like the scene's entities, and forgotten with the view, whose frames
 * rebuild them.</p>
 */
final class Paper26_2SceneBlocks {

    private static final String PREFIX = "play.clientbound.minecraft:";

    private final Map<String, Map<Long, Key>> blocks = new HashMap<>();

    /**
     * Observes one frame.
     *
     * @param typeName the frame's descriptor type name
     * @param decoder decodes the frame into its native packet
     */
    synchronized void observe(RawPacketFrame frame, String typeName, Function<RawPacketFrame, Object> decoder) {
        if (frame.world().isEmpty() || !typeName.startsWith(PREFIX)) {
            return;
        }
        String world = frame.world().get();
        switch (typeName.substring(PREFIX.length())) {
            case "level_chunk_with_light" -> {
                if (decoder.apply(frame) instanceof ClientboundLevelChunkWithLightPacket chunk) {
                    Map<Long, Key> known = forget(world, chunk.getX(), chunk.getZ());
                    chunk.getChunkData().getBlockEntitiesTagsConsumer(chunk.getX(), chunk.getZ())
                            .accept((pos, type, tag) -> {
                                if (Paper26_2BlockStates.recorded(type)) {
                                    known.put(pos.asLong(), key(type));
                                }
                            });
                }
            }
            case "forget_level_chunk" -> {
                if (decoder.apply(frame) instanceof ClientboundForgetLevelChunkPacket forget) {
                    forget(world, forget.pos().x(), forget.pos().z());
                }
            }
            case "block_update" -> {
                if (decoder.apply(frame) instanceof ClientboundBlockUpdatePacket update) {
                    set(world, update.getPos(), update.getBlockState());
                }
            }
            case "section_blocks_update" -> {
                if (decoder.apply(frame) instanceof ClientboundSectionBlocksUpdatePacket section) {
                    section.runUpdates((pos, state) -> set(world, pos, state));
                }
            }
            default -> { }
        }
    }

    /** The block entity type of the recorded block the scene shows at a position. */
    synchronized Optional<Key> block(String world, BlockPos pos) {
        return Optional.ofNullable(blocks.getOrDefault(world, Map.of()).get(pos.asLong()));
    }

    /** Forgets everything; the frames after a view reset rebuild it. */
    synchronized void clear() {
        blocks.clear();
    }

    private void set(String world, BlockPos pos, BlockState state) {
        Map<Long, Key> known = blocks.computeIfAbsent(world, ignored -> new HashMap<>());
        Optional<BlockEntityType<?>> type = Paper26_2BlockStates.recordedType(state);
        if (type.isPresent()) {
            known.put(pos.immutable().asLong(), key(type.get()));
        } else {
            known.remove(pos.asLong());
        }
    }

    /** Forgets the blocks of a chunk and returns the world's blocks. */
    private Map<Long, Key> forget(String world, int chunkX, int chunkZ) {
        Map<Long, Key> known = blocks.computeIfAbsent(world, ignored -> new HashMap<>());
        known.keySet().removeIf(packed -> SectionPos.blockToSectionCoord(BlockPos.getX(packed)) == chunkX
                && SectionPos.blockToSectionCoord(BlockPos.getZ(packed)) == chunkZ);
        return known;
    }

    private static Key key(BlockEntityType<?> type) {
        return Key.key(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
    }
}
