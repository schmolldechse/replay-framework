package dev.voldechse.replayframework.adapter.paper.v26_3.packet.definition.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id002AnimatePacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:animate";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAnimationAsEphemeralIdentityBearingEffect() {
        var definition = new Id002AnimatePacketDefinition();

        assertEquals(2, definition.packetId());
        assertEquals(0x02, Id002AnimatePacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
    }

    @Test
    void exposesTheCompleteVersionSpecificActionSet() {
        assertEquals(0, ClientboundAnimatePacket.WAKE_UP);
        assertEquals(1, ClientboundAnimatePacket.CRITICAL_HIT);
        assertEquals(2, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT);
    }

    @Test
    void preservesEveryUnsignedActionWhileRewritingIdentity() {
        var definition = new Id002AnimatePacketDefinition();
        var context = rewriter();
        int mappedEntityId = context.mapEntityId(41);

        for (int action = 0; action <= 0xFF; action++) {
            Id002AnimatePacketDefinition.Value value = definition.read(packet(41, action));
            Id002AnimatePacketDefinition.Value rewritten = definition
                    .rewrite(value, context)
                    .value()
                    .orElseThrow();
            ClientboundAnimatePacket rebuilt = definition.rebuild(rewritten);

            assertEquals(mappedEntityId, rebuilt.getId(), "action " + action);
            assertEquals(action, rebuilt.getAction(), "action " + action);
        }

        assertNotEquals(41, mappedEntityId);
    }

    @Test
    void rejectsActionsOutsideTheUnsignedByteRange() {
        assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id002AnimatePacketDefinition.Value(1, -1));
        assertThrows(
                IncompatibleAdapterException.class,
                () -> new Id002AnimatePacketDefinition.Value(1, 0x100));
    }

    @Test
    void handlesPlaybackAndValidatedEncode() {
        var definition = new Id002AnimatePacketDefinition();
        var context = rewriter();
        ClientboundAnimatePacket original =
                packet(51, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);
        ClientboundAnimatePacket rewritten = assertInstanceOf(
                ClientboundAnimatePacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(51), rewritten.getId());
        assertEquals(ClientboundAnimatePacket.MAGIC_CRITICAL_HIT, rewritten.getAction());
        assertEquals(2, definition.encode(rewritten, descriptor(), codec(original)).packetId());
    }

    @Test
    void nativeProtocolUsesIdTwoAndConsumesTheCompleteBody() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
        ClientboundAnimatePacket original = packet(61, 0xFF);
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, original);
            ByteBuf wireView = encoded.duplicate();
            assertEquals(2, readVarInt(wireView));
            assertEquals(61, readVarInt(wireView));
            assertEquals(0xFF, wireView.readUnsignedByte());
            assertFalse(wireView.isReadable());

            Packet<?> decoded = decode(protocol, encoded);
            ClientboundAnimatePacket animation = assertInstanceOf(
                    ClientboundAnimatePacket.class, decoded);
            assertFalse(encoded.isReadable());
            assertEquals(61, animation.getId());
            assertEquals(0xFF, animation.getAction());
        } finally {
            encoded.release();
        }
    }

    private static ClientboundAnimatePacket packet(int entityId, int action) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(action);
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
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
                2,
                PacketDisposition.EPHEMERAL,
                true,
                true,
                false,
                "paper-26.3/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 2, new byte[] {1});
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

    private static int readVarInt(ByteBuf buffer) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int current = buffer.readUnsignedByte();
            value |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new AssertionError("VarInt exceeds five bytes");
    }
}
