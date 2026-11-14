package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Paper26_2ReplayPacketRewriterTest {

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
        Paper26_2ReplayPacketRewriter rewriter = new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(1000));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse("minecraft:armor_stand"));
        ClientboundAddEntityPacket original = new ClientboundAddEntityPacket(
                17, RECORDED_ID, 1.0D, 2.0D, 3.0D, 4.0F, 5.0F,
                type, 0, Vec3.ZERO, 6.0D);
        PacketDescriptor descriptor = addEntityDescriptor();
        PacketCodec<Object> codec = codec(original);

        Paper26_2ReplayPacketRewriter.RewriteResult result = rewriter.rewrite(
                new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 1, new byte[] {1}),
                descriptor,
                codec);
        ClientboundAddEntityPacket rewritten = assertInstanceOf(
                ClientboundAddEntityPacket.class, result.packet());

        assertEquals(Paper26_2ReplayPacketRewriter.RewriteResult.Decision.SEND, result.decision());
        assertEquals(rewriter.mapEntityId(17), rewritten.getId());
        assertEquals(rewriter.mapUuid(RECORDED_ID), rewritten.getUUID());
        assertNotEquals(original.getId(), rewritten.getId());
        assertNotEquals(original.getUUID(), rewritten.getUUID());
        assertEquals(1, rewriter.encode(rewritten, descriptor, codec).packetId());

        ClientboundAnimatePacket animation = animation(
                17, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT);
        PacketDescriptor animationDescriptor = animateDescriptor();
        PacketCodec<Object> animationCodec = codec(animation);
        Paper26_2ReplayPacketRewriter.RewriteResult animationResult = rewriter.rewrite(
                new RawPacketFrame(1L, 1L, 0, PacketPhase.PLAY, 2, new byte[] {1}),
                animationDescriptor,
                animationCodec);
        ClientboundAnimatePacket rewrittenAnimation = assertInstanceOf(
                ClientboundAnimatePacket.class, animationResult.packet());

        assertEquals(rewritten.getId(), rewrittenAnimation.getId());
        assertEquals(animation.getAction(), rewrittenAnimation.getAction());
        assertEquals(
                2,
                rewriter.encode(
                                rewrittenAnimation, animationDescriptor, animationCodec)
                        .packetId());
    }

    @Test
    void viewResetClearsTrackedEntityTypesBeforeTheNextSnapshotIsReplayed() {
        Paper26_2ReplayPacketRewriter rewriter = new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(1000));
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse("minecraft:armor_stand"));
        int mappedId = rewriter.mapEntityId(17);
        rewriter.trackEntityType(17, type);

        rewriter.resetView();

        assertFalse(rewriter.recordedEntityType(17).isPresent());
        assertEquals(mappedId, rewriter.mapEntityId(17));
    }

    @Test
    void dropsAwardStatsBeforeCodecAtTheCatalogBoundary() {
        Paper26_2ReplayPacketRewriter rewriter = new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(1000));
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingViewerLocalCodec(decodes);

        Paper26_2ReplayPacketRewriter.RewriteResult result = rewriter.rewrite(
                new RawPacketFrame(2L, 2L, 0, PacketPhase.PLAY, 3, new byte[0]),
                awardStatsDescriptor(),
                codec);

        assertEquals(
                Paper26_2ReplayPacketRewriter.RewriteResult.Decision.DROP_LOCAL,
                result.decision());
        assertNull(result.packet());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
        assertEquals(0, decodes.get());
    }

    private static PacketDescriptor addEntityDescriptor() {
        return new PacketDescriptor(
                "play.clientbound.minecraft:add_entity",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                1,
                PacketDisposition.STATEFUL,
                true,
                true,
                true,
                "paper-26.2/play.clientbound.minecraft:add_entity",
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor animateDescriptor() {
        return new PacketDescriptor(
                "play.clientbound.minecraft:animate",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                2,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.2/play.clientbound.minecraft:animate",
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static PacketDescriptor awardStatsDescriptor() {
        return new PacketDescriptor(
                "play.clientbound.minecraft:award_stats",
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                3,
                PacketDisposition.STATEFUL,
                true,
                true,
                false,
                "paper-26.2/play.clientbound.minecraft:award_stats",
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static ClientboundAnimatePacket animation(int entityId, int action) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(action);
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
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

    private static PacketCodec<Object> codec(ClientboundAnimatePacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundAnimatePacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        2,
                        new byte[] {2});
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                return decoded;
            }
        };
    }

    private static PacketCodec<Object> failingViewerLocalCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("viewer-local award_stats must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local award_stats must not decode");
            }
        };
    }
}
