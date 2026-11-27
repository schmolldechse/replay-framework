package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketDistribution;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id123SwingAnimationPacketDefinitionTest {

    private static final int PACKET_ID = 123;
    private static final String TYPE_NAME = "play.clientbound.minecraft:swing_animation";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesArmSwingsAsEphemeralIdentityBearingSceneEffects() {
        var definition = new Id123SwingAnimationPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x7B, Id123SwingAnimationPacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(PacketDistribution.BROADCAST, definition.distribution());
    }

    @Test
    void registersExactlyOnceInEffectDomainCatalogAndPaperRegistry() {
        assertEquals(1, EffectPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .count());
        assertInstanceOf(
                Id123SwingAnimationPacketDefinition.class,
                Paper26_3ClientboundPacketCatalog.catalog().require(TYPE_NAME));
        var descriptor = Paper26_3PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertTrue(descriptor.replayable());
    }

    @Test
    void rewritesOnlyTheEntityIdAndKeepsHandAndAnimation() {
        var definition = new Id123SwingAnimationPacketDefinition();
        var context = rewriter();
        SwingAnimation animation = new SwingAnimation(SwingAnimationType.values()[0], 9);

        for (InteractionHand hand : InteractionHand.values()) {
            var original = new ClientboundSwingAnimationPacket(41, hand, animation);
            var rewritten = definition.rewrite(definition.read(original), context).value().orElseThrow();
            ClientboundSwingAnimationPacket rebuilt = definition.rebuild(rewritten);

            assertEquals(context.mapEntityId(41), rebuilt.entityId());
            assertEquals(hand, rebuilt.hand());
            assertEquals(animation, rebuilt.animation());
        }
        assertNotEquals(41, context.mapEntityId(41));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id123SwingAnimationPacketDefinition();
        var context = rewriter();
        var original = new ClientboundSwingAnimationPacket(
                51, InteractionHand.OFF_HAND, SwingAnimation.DEFAULT);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);
        ClientboundSwingAnimationPacket rewritten = assertInstanceOf(
                ClientboundSwingAnimationPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(51), rewritten.entityId());
        assertEquals(InteractionHand.OFF_HAND, rewritten.hand());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolRoundTripsTheSwing() {
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
        var original = new ClientboundSwingAnimationPacket(
                61, InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, original);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(61, VarInt.read(wire));

            var decoded = assertInstanceOf(
                    ClientboundSwingAnimationPacket.class, decode(protocol, encoded));
            assertEquals(original, decoded);
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
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundSwingAnimationPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundSwingAnimationPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID, new byte[0]);
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                return decoded;
            }
        };
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
