package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26PacketRegistry;
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

final class Paper26NativePacketCodecTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void encodesARealClientboundPacketWithoutTransportFraming() {
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();
        Paper26CheckpointEncoder.PacketBlueprint blueprint = new Paper26CheckpointEncoder.PacketBlueprint(
                "play.clientbound.minecraft:change_difficulty",
                Paper26CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                "minecraft:world",
                "global",
                new ClientboundChangeDifficultyPacket(Difficulty.NORMAL, false));
        Paper26NativePacketCodec codec = Paper26NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));

        Paper26CheckpointEncoder.EncodedPacket encoded = codec.encode(blueprint);

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
        assertEquals(2, payload.length);
        assertEquals(2, payload[0]);
        assertEquals(0, payload[1]);
        assertArrayEquals(payload, encoded.payload());
        assertTruePayloadIsDetached(encoded, payload);
    }

    @Test
    void rejectsDescriptorMismatchAndClosedCodec() {
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();
        Paper26NativePacketCodec codec = Paper26NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
        Paper26CheckpointEncoder.PacketBlueprint wrongDescriptor =
                new Paper26CheckpointEncoder.PacketBlueprint(
                        "play.clientbound.minecraft:set_time",
                        Paper26CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                        "minecraft:world",
                        "global",
                        new ClientboundChangeDifficultyPacket(Difficulty.NORMAL, false));

        assertThrows(IncompatibleAdapterException.class, () -> codec.encode(wrongDescriptor));
        codec.close();
        assertThrows(IllegalStateException.class, () -> codec.encode(wrongDescriptor));
    }

    @Test
    void acceptsEveryCheckpointRelevantDescriptorFromTheLiveRegistry() {
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();

        new Paper26CheckpointEncoder(
                registry,
                ignored -> CompletableFuture.completedFuture(
                        new Paper26CheckpointEncoder.CheckpointSnapshot(java.util.List.of())),
                ignored -> null,
                Runnable::run);
    }

    private static void assertTruePayloadIsDetached(
            Paper26CheckpointEncoder.EncodedPacket encoded,
            byte[] original) {
        original[0] = 99;
        if (Arrays.equals(original, encoded.payload())) {
            throw new AssertionError("encoded payload is not defensively copied");
        }
    }
}
