package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import dev.voldechse.replayframework.adapter.paper.protocol.PaperPacketRegistry;
import dev.voldechse.replayframework.adapter.paper.checkpoint.CheckpointSnapshot;
import dev.voldechse.replayframework.adapter.paper.checkpoint.EncodedPacket;
import dev.voldechse.replayframework.adapter.paper.checkpoint.PacketBlueprint;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily;

import dev.voldechse.replayframework.adapter.paper.checkpoint.CheckpointAssembler;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;

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
        PaperPacketRegistry registry = Paper26_2PacketRegistry.discover();
        PacketBlueprint blueprint = new PacketBlueprint(
                "play.clientbound.minecraft:set_border_center",
                PacketCheckpointFamily.GLOBAL_STATE,
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
        assertEquals(PacketCheckpointFamily.GLOBAL_STATE,
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
        PaperPacketRegistry registry = Paper26_2PacketRegistry.discover();
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
    void encodesSampledArmSwingAsEphemeralAnimateAndRejectsControlPackets() {
        PaperPacketRegistry registry = Paper26_2PacketRegistry.discover();
        Paper26_2NativePacketCodec codec = Paper26_2NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        final net.minecraft.network.protocol.game.ClientboundAnimatePacket swing;
        try {
            buffer.writeVarInt(42);
            buffer.writeByte(
                    net.minecraft.network.protocol.game.ClientboundAnimatePacket.SWING_MAIN_HAND);
            swing = net.minecraft.network.protocol.game.ClientboundAnimatePacket.STREAM_CODEC
                    .decode(buffer);
        } finally {
            buffer.release();
        }

        var encoded = codec.encodeState(swing);

        assertEquals(0x02, encoded.packetId());
        assertEquals("play.clientbound.minecraft:animate", encoded.descriptor().typeName());
        assertEquals(
                dev.voldechse.replayframework.adapter.PacketDisposition.EPHEMERAL,
                encoded.descriptor().disposition());
        // CONFIGURABLE chat is never sampled entity state.
        assertThrows(IncompatibleAdapterException.class, () -> codec.encodeState(
                new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(
                        net.minecraft.network.chat.Component.literal("chat"), false)));
    }

    @Test
    void rejectsDescriptorMismatchAndClosedCodec() {
        PaperPacketRegistry registry = Paper26_2PacketRegistry.discover();
        Paper26_2NativePacketCodec codec = Paper26_2NativePacketCodec.forTesting(
                registry,
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        net.minecraft.network.RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY)));
        PacketBlueprint wrongDescriptor =
                new PacketBlueprint(
                        "play.clientbound.minecraft:set_time",
                        PacketCheckpointFamily.GLOBAL_STATE,
                        "minecraft:world",
                        "global",
                        new ClientboundChangeDifficultyPacket(Difficulty.NORMAL, false));

        assertThrows(IncompatibleAdapterException.class, () -> codec.encode(wrongDescriptor));
        codec.close();
        assertThrows(IllegalStateException.class, () -> codec.encode(wrongDescriptor));
    }

    @Test
    void acceptsEveryCheckpointRelevantDescriptorFromTheLiveRegistry() {
        PaperPacketRegistry registry = Paper26_2PacketRegistry.discover();

        new CheckpointAssembler(
                registry,
                Paper26_2ClientboundPacketCatalog.catalog(),
                ignored -> CompletableFuture.completedFuture(
                        new CheckpointSnapshot(java.util.List.of())),
                ignored -> null,
                Runnable::run);
    }

    private static net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket borderCenter() {
        var border = new net.minecraft.world.level.border.WorldBorder();
        border.setCenter(32.0, -16.0);
        return new net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket(border);
    }

}
