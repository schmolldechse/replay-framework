package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import dev.voldechse.replayframework.adapter.paper.v26_3.argus.Paper26_3BlockStates;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/**
 * Builds the contents of a recorded block from the frames of its world's
 * block state track.
 *
 * <p>The track holds one {@code block_entity_data} packet per change of any
 * block of the world and a {@code block_update} where a block is gone. Both
 * begin with the block position, so the latest frame of a position is found
 * without decoding the others. That frame's data is loaded into a fresh block
 * entity of its type, which reads every container layout the game has, and
 * its items become a {@link SubjectState#CONTAINER} section. A double chest
 * is returned as one container: the right half's slots first, as the game
 * shows them.</p>
 */
final class Paper26_3BlockStateDecoder {

    private static final Map<BlockEntityType<?>, BlockState> STATES = new ConcurrentHashMap<>();

    private Paper26_3BlockStateDecoder() {
    }

    /**
     * @param target the block
     * @param frames the checkpoint and the frames up to the position, in order
     * @param isBlockState whether a frame is a {@code block_entity_data} or
     *                     {@code block_update} packet
     * @param decoder decodes a frame into its native packet
     * @param registries the registries item data refers to
     */
    static Optional<SubjectState> decode(
            BlockPosition target,
            List<RawPacketFrame> frames,
            Predicate<RawPacketFrame> isBlockState,
            Function<RawPacketFrame, Object> decoder,
            HolderLookup.Provider registries) {
        Map<Long, RawPacketFrame> latest = new HashMap<>();
        for (RawPacketFrame frame : frames) {
            if (frame.payload().length >= Long.BYTES && isBlockState.test(frame)) {
                latest.put(position(frame.payload()), frame);
            }
        }
        Optional<Block> block = block(latest.get(asLong(target)), decoder);
        if (block.isEmpty()) {
            return Optional.empty();
        }
        List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
        Optional<BlockPos> partner = block.get().partner();
        Optional<Block> other = partner.flatMap(pos -> block(latest.get(pos.asLong()), decoder));
        if (other.isPresent() && "left".equals(block.get().side())) {
            items.addAll(other.get().items(registries));
            items.addAll(block.get().items(registries));
        } else {
            items.addAll(block.get().items(registries));
            other.ifPresent(half -> items.addAll(half.items(registries)));
        }
        return Optional.of(new SubjectState(
                Map.of(),
                List.of(new SubjectState.Section(SubjectState.CONTAINER, items, OptionalInt.empty())),
                Optional.empty(),
                Optional.empty()));
    }

    /** The block of a frame; empty when there is none or it is gone. */
    private static Optional<Block> block(RawPacketFrame frame, Function<RawPacketFrame, Object> decoder) {
        if (frame == null || !(decoder.apply(frame) instanceof ClientboundBlockEntityDataPacket data)) {
            return Optional.empty();
        }
        return Optional.of(new Block(data.getPos(), data.getType(), data.getTag()));
    }

    /** Reads the position every block state packet starts with. */
    static long position(byte[] payload) {
        long packed = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            packed = (packed << 8) | (payload[index] & 0xFFL);
        }
        return packed;
    }

    private static long asLong(BlockPosition position) {
        return BlockPos.asLong(position.x(), position.y(), position.z());
    }

    /** A recorded block with its type and complete data. */
    private record Block(BlockPos pos, BlockEntityType<?> type, CompoundTag data) {

        Optional<BlockPos> partner() {
            return data.getIntArray(Paper26_3BlockStates.PARTNER_KEY)
                    .filter(coordinates -> coordinates.length == 3)
                    .map(coordinates -> new BlockPos(coordinates[0], coordinates[1], coordinates[2]));
        }

        String side() {
            return data.getStringOr(Paper26_3BlockStates.SIDE_KEY, "");
        }

        List<org.bukkit.inventory.ItemStack> items(HolderLookup.Provider registries) {
            BlockState state = validState(type);
            if (state == null) {
                return List.of();
            }
            BlockEntity entity = type.create(pos, state);
            if (!(entity instanceof Container container)) {
                return List.of();
            }
            entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, registries, data));
            List<org.bukkit.inventory.ItemStack> items = new ArrayList<>(container.getContainerSize());
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                items.add(CraftItemStack.asBukkitCopy(container.getItem(slot)));
            }
            return items;
        }
    }

    /** A block state the block entity type accepts, to create it outside a level. */
    private static BlockState validState(BlockEntityType<?> type) {
        return STATES.computeIfAbsent(type, wanted -> {
            for (net.minecraft.world.level.block.Block block : BuiltInRegistries.BLOCK) {
                if (wanted.isValid(block.defaultBlockState())) {
                    return block.defaultBlockState();
                }
            }
            return null;
        });
    }
}
