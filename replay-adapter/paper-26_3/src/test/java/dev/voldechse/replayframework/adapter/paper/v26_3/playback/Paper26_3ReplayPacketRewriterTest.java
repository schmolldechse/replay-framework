package dev.voldechse.replayframework.adapter.paper.v26_3.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Paper26_3ReplayPacketRewriterTest {

    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID RECORDED_ID = UUID.fromString("00000000-0000-0000-0000-000000000013");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void dispatchesDecodeRewriteRebuildAndEncodeThroughTheCatalog() {
        Paper26_3ReplayPacketRewriter rewriter = new Paper26_3ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(1000));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse("minecraft:armor_stand"));
        ClientboundAddEntityPacket original = new ClientboundAddEntityPacket(
                17, RECORDED_ID, 1.0D, 2.0D, 3.0D, 4.0F, 5.0F,
                type, 0, Vec3.ZERO, 6.0D);
        PacketDescriptor descriptor = descriptor();
        PacketCodec<Object> codec = codec(original);

        Paper26_3ReplayPacketRewriter.RewriteResult result = rewriter.rewrite(
                new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 1, new byte[] {1}),
                descriptor,
                codec);
        ClientboundAddEntityPacket rewritten = assertInstanceOf(
                ClientboundAddEntityPacket.class, result.packet());

        assertEquals(Paper26_3ReplayPacketRewriter.RewriteResult.Decision.SEND, result.decision());
        assertEquals(rewriter.mapEntityId(17), rewritten.getId());
        assertEquals(rewriter.mapUuid(RECORDED_ID), rewritten.getUUID());
        assertNotEquals(original.getId(), rewritten.getId());
        assertNotEquals(original.getUUID(), rewritten.getUUID());
        assertEquals(1, rewriter.encode(rewritten, descriptor, codec).packetId());
    }

    private static PacketDescriptor descriptor() {
        return new PacketDescriptor(
                "play.clientbound.minecraft:add_entity",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                1,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.3/play.clientbound.minecraft:add_entity",
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketCodec<Object> codec(ClientboundAddEntityPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundAddEntityPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        1,
                        new byte[] {2});
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                return decoded;
            }
        };
    }
}
