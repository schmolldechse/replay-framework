package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.voldechse.replayframework.adapter.paper.playback.scene.SceneModel;
import dev.voldechse.replayframework.api.recording.BlockPosition;
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

/** Containers through the 26.3 scene decoder and the shared scene model. */
class Paper26_3SceneDecoderBlocksTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final BlockPosition CHEST = new BlockPosition(4, 64, 9);
    private static final BlockPos CHEST_POS = new BlockPos(4, 64, 9);

    private final Map<RawPacketFrame, Object> packets = new IdentityHashMap<>();
    private final SceneModel scene = new SceneModel(new Paper26_3SceneDecoder(packets::get));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void followsContainersPlacedAndBroken() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST_POS, Blocks.BARREL.defaultBlockState()));
        assertEquals(Optional.of(Key.key("minecraft:barrel")), scene.container(OVERWORLD, CHEST));
        assertEquals(Optional.empty(), scene.container("minecraft:the_nether", CHEST));

        observe("block_update", new ClientboundBlockUpdatePacket(CHEST_POS, Blocks.STONE.defaultBlockState()));
        assertEquals(Optional.empty(), scene.container(OVERWORLD, CHEST));
    }

    @Test
    void forgetsTheContainersOfAForgottenChunk() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST_POS, Blocks.CHEST.defaultBlockState()));
        observe("forget_level_chunk", new ClientboundForgetLevelChunkPacket(new ChunkPos(0, 0)));

        assertEquals(Optional.empty(), scene.container(OVERWORLD, CHEST));
    }

    @Test
    void ignoresBlocksWithoutRecordedContents() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST_POS, Blocks.ENDER_CHEST.defaultBlockState()));

        assertEquals(Optional.empty(), scene.container(OVERWORLD, CHEST));
    }

    @Test
    void forgetsEverythingWithTheView() {
        observe("block_update", new ClientboundBlockUpdatePacket(CHEST_POS, Blocks.HOPPER.defaultBlockState()));
        scene.clear();

        assertEquals(Optional.empty(), scene.container(OVERWORLD, CHEST));
    }

    private void observe(String type, Object packet) {
        RawPacketFrame frame = new RawPacketFrame(0L, 0L, packets.size(), PacketPhase.PLAY, 1, new byte[0],
                Optional.of(OVERWORLD));
        packets.put(frame, packet);
        scene.observe(frame, "play.clientbound.minecraft:" + type);
    }
}
