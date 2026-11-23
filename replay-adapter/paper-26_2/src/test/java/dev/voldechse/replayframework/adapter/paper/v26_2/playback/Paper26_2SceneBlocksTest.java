package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Paper26_2SceneBlocksTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final BlockPos CHEST = new BlockPos(4, 64, 9);

    private final Map<RawPacketFrame, Object> packets = new IdentityHashMap<>();
    private final Paper26_2SceneBlocks blocks = new Paper26_2SceneBlocks();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void followsContainersPlacedAndBroken() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST, Blocks.BARREL.defaultBlockState()));
        assertEquals(Optional.of(Key.key("minecraft:barrel")), blocks.block(OVERWORLD, CHEST));
        assertEquals(Optional.empty(), blocks.block("minecraft:the_nether", CHEST));

        observe("block_update", new ClientboundBlockUpdatePacket(CHEST, Blocks.STONE.defaultBlockState()));
        assertEquals(Optional.empty(), blocks.block(OVERWORLD, CHEST));
    }

    @Test
    void forgetsTheContainersOfAForgottenChunk() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST, Blocks.CHEST.defaultBlockState()));
        observe("forget_level_chunk", new ClientboundForgetLevelChunkPacket(new ChunkPos(0, 0)));

        assertEquals(Optional.empty(), blocks.block(OVERWORLD, CHEST));
    }

    @Test
    void ignoresBlocksWithoutRecordedContents() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST, Blocks.ENDER_CHEST.defaultBlockState()));

        assertEquals(Optional.empty(), blocks.block(OVERWORLD, CHEST));
    }

    @Test
    void forgetsEverythingWithTheView() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST, Blocks.HOPPER.defaultBlockState()));
        blocks.clear();

        assertEquals(Optional.empty(), blocks.block(OVERWORLD, CHEST));
    }

    private void observe(String type, Object packet) {
        RawPacketFrame frame = new RawPacketFrame(0L, 0L, packets.size(), PacketPhase.PLAY, 1, new byte[0],
                Optional.of(OVERWORLD));
        packets.put(frame, packet);
        blocks.observe(frame, "play.clientbound.minecraft:" + type, packets::get);
    }
}
