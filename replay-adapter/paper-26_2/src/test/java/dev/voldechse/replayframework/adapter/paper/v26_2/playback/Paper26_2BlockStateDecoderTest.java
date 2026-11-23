package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.paper.v26_2.argus.Paper26_2BlockStates;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_2BlockStateDecoderTest {

    private static final BlockPos LEFT = new BlockPos(10, 64, 20);
    private static final BlockPos RIGHT = new BlockPos(11, 64, 20);
    private static RegistryAccess registries;

    private final Map<RawPacketFrame, Object> packets = new IdentityHashMap<>();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void theLatestFrameOfAPositionHoldsItsContents() {
        List<RawPacketFrame> frames = List.of(
                frame(hopper(LEFT, item(Items.STONE, 1))),
                frame(hopper(RIGHT, item(Items.DIRT, 5))),
                frame(hopper(LEFT, item(Items.DIAMOND, 2))));

        SubjectState state = decode(LEFT, frames).orElseThrow();

        List<org.bukkit.inventory.ItemStack> items = state.section(SubjectState.CONTAINER).orElseThrow().items();
        assertEquals(5, items.size());
        assertEquals(org.bukkit.Material.DIAMOND, items.get(0).getType());
    }

    @Test
    void aBlockThatIsGoneHasNoContents() {
        List<RawPacketFrame> frames = List.of(
                frame(hopper(LEFT, item(Items.STONE, 1))),
                frame(new ClientboundBlockUpdatePacket(LEFT, Blocks.AIR.defaultBlockState())));

        assertEquals(Optional.empty(), decode(LEFT, frames));
        assertEquals(Optional.empty(), decode(RIGHT, frames));
    }

    @Test
    void aDoubleChestIsOneContainerWithTheRightHalfFirst() {
        List<RawPacketFrame> frames = List.of(
                frame(chest(LEFT, RIGHT, "left", item(Items.STONE, 1))),
                frame(chest(RIGHT, LEFT, "right", item(Items.DIAMOND, 1))));

        for (BlockPos clicked : List.of(LEFT, RIGHT)) {
            List<org.bukkit.inventory.ItemStack> items = decode(clicked, frames).orElseThrow()
                    .section(SubjectState.CONTAINER).orElseThrow().items();
            assertEquals(54, items.size());
            assertEquals(org.bukkit.Material.DIAMOND, items.get(0).getType());
            assertEquals(org.bukkit.Material.STONE, items.get(27).getType());
        }
    }

    @Test
    void readsThePositionAtTheStartOfThePayload() {
        assertEquals(LEFT.asLong(), Paper26_2BlockStateDecoder.position(
                ByteBuffer.allocate(12).putLong(LEFT.asLong()).putInt(7).array()));
    }

    private Optional<SubjectState> decode(BlockPos pos, List<RawPacketFrame> frames) {
        return Paper26_2BlockStateDecoder.decode(
                new BlockPosition(pos.getX(), pos.getY(), pos.getZ()), frames, frame -> true, packets::get, registries);
    }

    private RawPacketFrame frame(Object packet) {
        BlockPos pos = packet instanceof ClientboundBlockEntityDataPacket data
                ? data.getPos()
                : ((ClientboundBlockUpdatePacket) packet).getPos();
        RawPacketFrame frame = new RawPacketFrame(0L, 0L, packets.size(), PacketPhase.PLAY, 6,
                ByteBuffer.allocate(Long.BYTES).putLong(pos.asLong()).array());
        packets.put(frame, packet);
        return frame;
    }

    private static ClientboundBlockEntityDataPacket hopper(BlockPos pos, ItemStack first) {
        HopperBlockEntity hopper = new HopperBlockEntity(pos, Blocks.HOPPER.defaultBlockState());
        hopper.setItem(0, first);
        return packet(pos, BlockEntityTypes.HOPPER, hopper.saveWithoutMetadata(registries));
    }

    private static ClientboundBlockEntityDataPacket chest(BlockPos pos, BlockPos partner, String side, ItemStack first) {
        ChestBlockEntity chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setItem(0, first);
        CompoundTag data = chest.saveWithoutMetadata(registries);
        data.putIntArray(Paper26_2BlockStates.PARTNER_KEY, new int[] {partner.getX(), partner.getY(), partner.getZ()});
        data.putString(Paper26_2BlockStates.SIDE_KEY, side);
        return packet(pos, BlockEntityTypes.CHEST, data);
    }

    private static ClientboundBlockEntityDataPacket packet(BlockPos pos, BlockEntityType<?> type, CompoundTag data) {
        try {
            var constructor = ClientboundBlockEntityDataPacket.class.getDeclaredConstructor(
                    BlockPos.class, BlockEntityType.class, CompoundTag.class);
            constructor.setAccessible(true);
            return constructor.newInstance(pos, type, data);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ItemStack item(Item type, int count) {
        var holder = type.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        }
        return new ItemStack(holder, count);
    }

    @Test
    void recordsContainersButNotEnderChests() {
        assertTrue(Paper26_2BlockStates.recorded(BlockEntityTypes.CHEST));
        assertTrue(Paper26_2BlockStates.recordedType(Blocks.BARREL.defaultBlockState()).isPresent());
        assertTrue(Paper26_2BlockStates.recordedType(Blocks.ENDER_CHEST.defaultBlockState()).isEmpty());
        assertTrue(Paper26_2BlockStates.recordedType(Blocks.STONE.defaultBlockState()).isEmpty());
    }
}
