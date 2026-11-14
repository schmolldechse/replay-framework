package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
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
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id124TakeItemEntityPacketDefinitionTest {

    private static final int PACKET_ID = 124;
    private static final String TYPE_NAME = "play.clientbound.minecraft:take_item_entity";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesPickupAnimationAsEphemeralIdentityBearingSceneEffect() {
        var definition = new Id124TakeItemEntityPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x7C, Id124TakeItemEntityPacketDefinition.PACKET_ID);
        assertEquals("minecraft:take_item_entity", Id124TakeItemEntityPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInEffectCatalogAndPaperRegistry() {
        var domainMatches = EffectPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id124TakeItemEntityPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id124TakeItemEntityPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void rewritesCollectedItemAndCollectorIdsAndPreservesStackAmount() {
        var definition = new Id124TakeItemEntityPacketDefinition();
        var context = rewriter();
        ClientboundTakeItemEntityPacket original = packet(41, 73, 5);

        PacketRewriteResult result = definition.handlePlayback(
                frame(), descriptor(), codec(original), context);
        ClientboundTakeItemEntityPacket rewritten = assertInstanceOf(
                ClientboundTakeItemEntityPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(41), rewritten.getItemId());
        assertEquals(context.mapEntityId(73), rewritten.getPlayerId());
        assertEquals(5, rewritten.getAmount());
        assertNotEquals(41, rewritten.getItemId());
        assertNotEquals(73, rewritten.getPlayerId());
        assertEquals(PACKET_ID, definition.encode(rewritten, descriptor(), codec(original)).packetId());
    }

    @Test
    void preservesEveryVarIntAmountAndEntityIdValueWithoutSentinelInterpretation() {
        var definition = new Id124TakeItemEntityPacketDefinition();
        var context = rewriter();

        for (int value : new int[] {Integer.MIN_VALUE, -1, 0, 1, 127, 128, Integer.MAX_VALUE}) {
            Id124TakeItemEntityPacketDefinition.Value rewritten = definition
                    .rewrite(new Id124TakeItemEntityPacketDefinition.Value(value, value + 1, value), context)
                    .value()
                    .orElseThrow();
            assertEquals(context.mapEntityId(value), rewritten.itemEntityId(), "item id " + value);
            assertEquals(context.mapEntityId(value + 1), rewritten.collectorEntityId(), "collector id " + value);
            assertEquals(value, rewritten.amount(), "amount " + value);
        }
    }

    @Test
    void nativeProtocolUsesId124AndRoundTripsThreeVarIntsInWireOrder() {
        ProtocolInfo<?> protocol = protocol();

        for (int[] values : new int[][] {
            {0, 1, 0},
            {127, 128, 1},
            {Integer.MAX_VALUE, Integer.MIN_VALUE, 64},
            {-1, 0, -1}
        }) {
            ClientboundTakeItemEntityPacket original = packet(values[0], values[1], values[2]);
            ByteBuf encoded = Unpooled.buffer();
            try {
                encode(protocol, encoded, original);
                ByteBuf wireView = encoded.duplicate();
                assertEquals(PACKET_ID, readVarInt(wireView));
                assertEquals(values[0], readVarInt(wireView));
                assertEquals(values[1], readVarInt(wireView));
                assertEquals(values[2], readVarInt(wireView));
                assertFalse(wireView.isReadable());

                ClientboundTakeItemEntityPacket decoded = assertInstanceOf(
                        ClientboundTakeItemEntityPacket.class, decode(protocol, encoded));
                assertFalse(encoded.isReadable());
                assertEquals(values[0], decoded.getItemId());
                assertEquals(values[1], decoded.getPlayerId());
                assertEquals(values[2], decoded.getAmount());
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void nativeProtocolRejectsEachTruncatedFieldBoundary() {
        ProtocolInfo<?> protocol = protocol();

        for (byte[] payload : new byte[][] {
            {(byte) PACKET_ID},
            {(byte) PACKET_ID, 1},
            {(byte) PACKET_ID, 1, 2},
            {(byte) PACKET_ID, 1, 2, (byte) 0x80}
        }) {
            ByteBuf truncated = Unpooled.wrappedBuffer(payload);
            try {
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
            } finally {
                truncated.release();
            }
        }
    }

    private static ClientboundTakeItemEntityPacket packet(
            int itemEntityId, int collectorEntityId, int amount) {
        return new ClientboundTakeItemEntityPacket(itemEntityId, collectorEntityId, amount);
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
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
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame() {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, new byte[] {1});
    }

    private static PacketCodec<Object> codec(ClientboundTakeItemEntityPacket decoded) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                assertInstanceOf(ClientboundTakeItemEntityPacket.class, packet);
                return new EncodedPacket(
                        PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND,
                        PACKET_ID,
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
