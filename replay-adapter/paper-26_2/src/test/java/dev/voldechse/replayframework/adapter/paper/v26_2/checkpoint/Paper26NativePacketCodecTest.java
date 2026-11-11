package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26PacketRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class Paper26NativePacketCodecTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void rejectsCheckpointEncodingWithoutAPacketImplementation() {
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();
        Paper26CheckpointEncoder.PacketBlueprint blueprint = new Paper26CheckpointEncoder.PacketBlueprint(
                "play.clientbound.minecraft:set_border_center",
                Paper26CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                "minecraft:world",
                "global",
                borderCenter());
        Paper26NativePacketCodec codec = Paper26NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));

        assertThrows(IncompatibleAdapterException.class, () -> codec.encode(blueprint));
    }

    @Test
    void rejectsSyntheticStateWithoutAPacketImplementation() {
        Paper26PacketRegistry registry = Paper26PacketRegistry.discover();
        Paper26NativePacketCodec codec = Paper26NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
        var values = new net.minecraft.world.entity.PositionMoveRotation(
                new net.minecraft.world.phys.Vec3(1.25, 64.0, -2.5),
                net.minecraft.world.phys.Vec3.ZERO,
                90.0F,
                15.0F);

        assertThrows(IncompatibleAdapterException.class, () -> codec.encodeState(
                new net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket(
                        42, values, true)));
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

    private static net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket borderCenter() {
        var border = new net.minecraft.world.level.border.WorldBorder();
        border.setCenter(32.0, -16.0);
        return new net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket(border);
    }

}
