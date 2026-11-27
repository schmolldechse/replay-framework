package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.capture.Paper26_3CaptureTestState;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.PacketPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddTransientBlockPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id037AddTransientBlockPacketDefinitionTest {

    private static final int PACKET_ID = 37;
    private static final String TYPE_NAME = "play.clientbound.minecraft:add_transient_block";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesTheTransientBlockAsAnEphemeralSafeEffect() {
        var definition = new Id037AddTransientBlockPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x25, Id037AddTransientBlockPacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.SAFE, definition.classification().playbackScope());
        assertEquals(PacketDistribution.BROADCAST, definition.distribution());
    }

    @Test
    void registersExactlyOnceInWorldDomainCatalogAndPaperRegistry() {
        assertEquals(1, WorldPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .count());
        assertInstanceOf(
                Id037AddTransientBlockPacketDefinition.class,
                Paper26_3ClientboundPacketCatalog.catalog().require(TYPE_NAME));
        var descriptor = Paper26_3PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
    }

    @Test
    void placesTheEffectAtItsBlockAndReplaysItUnchanged() {
        var definition = new Id037AddTransientBlockPacketDefinition();
        var position = new BlockPos(-7, 81, 12);
        var original = new ClientboundAddTransientBlockPacket(position, Blocks.SAND.defaultBlockState());

        CaptureContext context = definition.captureContext(
                original, descriptor(), UUID.randomUUID(), Paper26_3CaptureTestState.create(), 5L);
        assertEquals(Optional.of(new BlockPosition(-7, 81, 12)), context.position());

        var rewritten = definition.rewrite(definition.read(original), rewriter()).value().orElseThrow();
        ClientboundAddTransientBlockPacket rebuilt = definition.rebuild(rewritten);
        assertEquals(position, rebuilt.getPos());
        assertEquals(Blocks.SAND.defaultBlockState(), rebuilt.getBlockState());
    }

    @Test
    void nativeProtocolRoundTripsPositionAndState() {
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
        var original = new ClientboundAddTransientBlockPacket(
                new BlockPos(3, -60, 9), Blocks.GRAVEL.defaultBlockState());
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, original);
            assertEquals(PACKET_ID, VarInt.read(encoded.duplicate()));

            var decoded = assertInstanceOf(
                    ClientboundAddTransientBlockPacket.class, decode(protocol, encoded));
            assertEquals(original.getPos(), decoded.getPos());
            assertEquals(original.getBlockState(), decoded.getBlockState());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                PACKET_ID,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.SAFE);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }
}
