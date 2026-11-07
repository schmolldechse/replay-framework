package dev.voldechse.replayframework.adapter.paper.v26_3.checkpoint;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Paper26_3NativePacketCodecTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void encodesARealClientboundPacketWithoutTransportFraming() {
        Paper26_3PacketRegistry registry = Paper26_3PacketRegistry.discover();
        Paper26_3CheckpointEncoder.PacketBlueprint blueprint = new Paper26_3CheckpointEncoder.PacketBlueprint(
                "play.clientbound.minecraft:set_border_center",
                Paper26_3CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                "minecraft:world",
                "global",
                borderCenter());
        Paper26_3NativePacketCodec codec = Paper26_3NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));

        Paper26_3CheckpointEncoder.EncodedPacket encoded = codec.encode(blueprint);

        int expectedId = registry.find(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        encoded.packetId())
                .orElseThrow()
                .packetId();
        assertEquals(expectedId, encoded.packetId());
        assertEquals(PacketPhase.PLAY, encoded.phase());
        assertEquals(PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
        byte[] payload = encoded.payload();
        assertEquals(16, payload.length);
        var buffer = new net.minecraft.network.FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            assertEquals(32.0, buffer.readDouble());
            assertEquals(-16.0, buffer.readDouble());
        } finally {
            buffer.release();
        }
        assertArrayEquals(payload, encoded.payload());
        assertTruePayloadIsDetached(encoded, payload);
    }

    @Test
    void rejectsDescriptorMismatchAndClosedCodec() {
        Paper26_3PacketRegistry registry = Paper26_3PacketRegistry.discover();
        Paper26_3NativePacketCodec codec = Paper26_3NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
        Paper26_3CheckpointEncoder.PacketBlueprint wrongDescriptor =
                new Paper26_3CheckpointEncoder.PacketBlueprint(
                        "play.clientbound.minecraft:set_time",
                        Paper26_3CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                        "minecraft:world",
                        "global",
                        new ClientboundChangeDifficultyPacket(Difficulty.NORMAL, false));

        assertThrows(IncompatibleAdapterException.class, () -> codec.encode(wrongDescriptor));
        codec.close();
        assertThrows(IllegalStateException.class, () -> codec.encode(wrongDescriptor));
    }

    @Test
    void acceptsEveryCheckpointRelevantDescriptorFromTheLiveRegistry() {
        Paper26_3PacketRegistry registry = Paper26_3PacketRegistry.discover();

        new Paper26_3CheckpointEncoder(
                registry,
                ignored -> CompletableFuture.completedFuture(
                        new Paper26_3CheckpointEncoder.CheckpointSnapshot(java.util.List.of())),
                ignored -> null,
                Runnable::run);
    }

    private static net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket borderCenter() {
        var border = new net.minecraft.world.level.border.WorldBorder();
        border.setCenter(32.0, -16.0);
        return new net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket(border);
    }

    private static void assertTruePayloadIsDetached(
            Paper26_3CheckpointEncoder.EncodedPacket encoded,
            byte[] original) {
        original[0] = 99;
        if (Arrays.equals(original, encoded.payload())) {
            throw new AssertionError("encoded payload is not defensively copied");
        }
    }
}
