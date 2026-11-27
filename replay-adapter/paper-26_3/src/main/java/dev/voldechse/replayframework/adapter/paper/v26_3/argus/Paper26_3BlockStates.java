package dev.voldechse.replayframework.adapter.paper.v26_3.argus;

import dev.voldechse.replayframework.adapter.paper.argus.BlockSample;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reads the blocks of a Paper 26.3 level whose contents no client receives
 * without opening them, and builds the packets that carry them.
 *
 * <p>Recorded are chests, trapped and copper chests, barrels, shulker boxes,
 * hoppers, dispensers, droppers, crafters, furnaces, blast furnaces, smokers,
 * brewing stands, chiseled bookshelves, shelves, decorated pots and
 * jukeboxes. An ender chest is not: its contents belong to the player who
 * opens it.</p>
 *
 * <p>A block's state is one {@code block_entity_data} packet with the block
 * entity's complete data, items included. The server sends this packet with
 * less data to clients; the recording keeps the full data and never replays
 * it. A half of a double chest also names its other half, so playback can
 * show both as one container. A block that is gone is recorded as a
 * {@code block_update} to air at its position.</p>
 *
 * <p>Called on the server thread. The packets hold detached copies, so they
 * can be encoded later on another thread.</p>
 */
public final class Paper26_3BlockStates {

    /** Data key of the position of a double chest's other half. */
    public static final String PARTNER_KEY = "replay_framework:partner";
    /** Data key of a double chest half's side: {@code left} or {@code right}. */
    public static final String SIDE_KEY = "replay_framework:side";

    private static final Set<BlockEntityType<?>> RECORDED = Set.of(
            BlockEntityTypes.CHEST, BlockEntityTypes.TRAPPED_CHEST, BlockEntityTypes.BARREL,
            BlockEntityTypes.SHULKER_BOX, BlockEntityTypes.HOPPER, BlockEntityTypes.DISPENSER,
            BlockEntityTypes.DROPPER, BlockEntityTypes.CRAFTER, BlockEntityTypes.FURNACE,
            BlockEntityTypes.BLAST_FURNACE, BlockEntityTypes.SMOKER, BlockEntityTypes.BREWING_STAND,
            BlockEntityTypes.CHISELED_BOOKSHELF, BlockEntityTypes.SHELF, BlockEntityTypes.DECORATED_POT,
            BlockEntityTypes.JUKEBOX);

    private Paper26_3BlockStates() {
    }

    /** Whether the playback should treat blocks of this type as containers. */
    public static boolean recorded(BlockEntityType<?> type) {
        return RECORDED.contains(type);
    }

    /** The recorded block entity type a block state belongs to, if any. */
    public static Optional<BlockEntityType<?>> recordedType(BlockState state) {
        if (!state.hasBlockEntity()) {
            return Optional.empty();
        }
        for (BlockEntityType<?> type : RECORDED) {
            if (type.isValid(state)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Looks at the recorded blocks of one chunk, for Argus; empty when it is not loaded. */
    public static Optional<List<BlockSample>> samples(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return Optional.empty();
        }
        List<BlockSample> samples = new ArrayList<>();
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (entity instanceof Container container && recorded(entity.getType())) {
                BlockPos pos = entity.getBlockPos();
                samples.add(new BlockSample(
                        new BlockPosition(pos.getX(), pos.getY(), pos.getZ()),
                        signature(entity, container),
                        () -> packet(entity)));
            }
        }
        return Optional.of(samples);
    }

    /** The state of every recorded block of a chunk, for a checkpoint. */
    public static List<Packet<?>> snapshot(LevelChunk chunk) {
        List<Packet<?>> packets = new ArrayList<>();
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (entity instanceof Container && recorded(entity.getType())) {
                packets.add(packet(entity));
            }
        }
        return packets;
    }

    /** The packet that records that the block at a position is gone. */
    public static Packet<?> removed(BlockPosition position) {
        return new ClientboundBlockUpdatePacket(
                new BlockPos(position.x(), position.y(), position.z()), Blocks.AIR.defaultBlockState());
    }

    /** The block entity's complete data, with the other half of a double chest. */
    static ClientboundBlockEntityDataPacket packet(BlockEntity entity) {
        return ClientboundBlockEntityDataPacket.create(entity, (block, registries) -> {
            CompoundTag data = block.saveWithoutMetadata(registries);
            BlockState state = block.getBlockState();
            if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos partner = block.getBlockPos().relative(ChestBlock.getConnectedDirection(state));
                data.putIntArray(PARTNER_KEY, new int[] {partner.getX(), partner.getY(), partner.getZ()});
                data.putString(SIDE_KEY, state.getValue(ChestBlock.TYPE) == ChestType.LEFT ? "left" : "right");
            }
            return data;
        });
    }

    private static long signature(BlockEntity entity, Container container) {
        long signature = 1L;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack item = container.getItem(slot);
            signature = 31L * signature + ItemStack.hashItemAndComponents(item);
            signature = 31L * signature + item.getCount();
        }
        BlockState state = entity.getBlockState();
        if (state.hasProperty(ChestBlock.TYPE)) {
            signature = 31L * signature + state.getValue(ChestBlock.TYPE).ordinal();
        }
        return signature;
    }
}
