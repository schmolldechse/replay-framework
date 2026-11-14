package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Paper26_2NativePacketCodecTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void encodesCheckpointRelevantBorderCenterState() {
        Paper26_2PacketRegistry registry = Paper26_2PacketRegistry.discover();
        Paper26_2CheckpointEncoder.PacketBlueprint blueprint = new Paper26_2CheckpointEncoder.PacketBlueprint(
                "play.clientbound.minecraft:set_border_center",
                Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                "minecraft:world",
                "global",
                borderCenter());
        Paper26_2NativePacketCodec codec = Paper26_2NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));

        var encoded = codec.encode(blueprint);

        assertEquals("play.clientbound.minecraft:set_border_center", encoded.descriptorTypeName());
        assertEquals(PacketPhase.PLAY, encoded.phase());
        assertEquals(
                PacketDescriptor.Direction.CLIENTBOUND, encoded.direction());
        assertEquals(0x58, encoded.packetId());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                encoded.family());
        assertEquals("minecraft:world", encoded.worldKey());
        assertEquals("global", encoded.targetKey());
        var payload = io.netty.buffer.Unpooled.wrappedBuffer(encoded.payload());
        try {
            assertEquals(32.0, payload.readDouble());
            assertEquals(-16.0, payload.readDouble());
            assertFalse(payload.isReadable());
        } finally {
            payload.release();
        }
    }

    @Test
    void encodesImplementedEntityPositionSyncState() {
        Paper26_2PacketRegistry registry = Paper26_2PacketRegistry.discover();
        Paper26_2NativePacketCodec codec = Paper26_2NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
        var values = new net.minecraft.world.entity.PositionMoveRotation(
                new net.minecraft.world.phys.Vec3(1.25, 64.0, -2.5),
                net.minecraft.world.phys.Vec3.ZERO,
                90.0F,
                15.0F);

        var encoded = codec.encodeState(
                new net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket(
                        42, values, true));

        assertEquals(35, encoded.packetId());
        assertEquals(58, encoded.payload().length);
        assertEquals("play.clientbound.minecraft:entity_position_sync", encoded.descriptor().typeName());
        assertEquals(
                dev.voldechse.replayframework.adapter.PacketDisposition.STATEFUL,
                encoded.descriptor().disposition());
        assertTrue(encoded.descriptor().checkpointRelevant());
    }

    @Test
    void rejectsDescriptorMismatchAndClosedCodec() {
        Paper26_2PacketRegistry registry = Paper26_2PacketRegistry.discover();
        Paper26_2NativePacketCodec codec = Paper26_2NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
        Paper26_2CheckpointEncoder.PacketBlueprint wrongDescriptor =
                new Paper26_2CheckpointEncoder.PacketBlueprint(
                        "play.clientbound.minecraft:set_time",
                        Paper26_2CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                        "minecraft:world",
                        "global",
                        new ClientboundChangeDifficultyPacket(Difficulty.NORMAL, false));

        assertThrows(IncompatibleAdapterException.class, () -> codec.encode(wrongDescriptor));
        codec.close();
        assertThrows(IllegalStateException.class, () -> codec.encode(wrongDescriptor));
    }

    @Test
    void acceptsEveryCheckpointRelevantDescriptorFromTheLiveRegistry() {
        Paper26_2PacketRegistry registry = Paper26_2PacketRegistry.discover();

        new Paper26_2CheckpointEncoder(
                registry,
                ignored -> CompletableFuture.completedFuture(
                        new Paper26_2CheckpointEncoder.CheckpointSnapshot(java.util.List.of())),
                ignored -> null,
                Runnable::run);
    }

    private static net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket borderCenter() {
        var border = new net.minecraft.world.level.border.WorldBorder();
        border.setCenter(32.0, -16.0);
        return new net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket(border);
    }

}
