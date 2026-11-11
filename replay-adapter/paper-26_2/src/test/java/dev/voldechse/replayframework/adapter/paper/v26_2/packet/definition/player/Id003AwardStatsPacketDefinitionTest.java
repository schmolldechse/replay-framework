package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id003AwardStatsPacketDefinitionTest {

    private static final String TYPE_NAME = "play.clientbound.minecraft:award_stats";
    private static final UUID OBSERVER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesStatisticsAsViewerLocalPlayerState() {
        var definition = new Id003AwardStatsPacketDefinition();

        assertEquals(3, definition.packetId());
        assertEquals(0x03, Id003AwardStatsPacketDefinition.PACKET_ID);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    @Test
    void dropsEveryPayloadBeforeDecode() {
        var definition = new Id003AwardStatsPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80}
        }) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
            assertEquals(
                    "recorded connection state does not belong to the observer",
                    result.reason());
        }

        assertEquals(0, decodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingTheDecodedType() {
        var definition = new Id003AwardStatsPacketDefinition();

        PacketRewriteResult result = definition.rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void nativeProtocolUsesIdThreeAndRoundTripsEveryStatCategory() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        ProtocolInfo<?> protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));

        assertNativeRoundTrip(
                protocol, new Object2IntOpenHashMap<>(), 0);
        assertNativeRoundTrip(protocol, everyStatCategory(), 9);
    }

    private static Object2IntMap<Stat<?>> everyStatCategory() {
        Object2IntMap<Stat<?>> stats = new Object2IntOpenHashMap<>();
        stats.put(Stats.BLOCK_MINED.get(Blocks.STONE), 0);
        stats.put(Stats.ITEM_CRAFTED.get(Items.CRAFTING_TABLE), 1);
        stats.put(Stats.ITEM_USED.get(Items.DIAMOND_PICKAXE), -1);
        stats.put(Stats.ITEM_BROKEN.get(Items.IRON_SHOVEL), Integer.MAX_VALUE);
        stats.put(Stats.ITEM_PICKED_UP.get(Items.APPLE), Integer.MIN_VALUE);
        stats.put(Stats.ITEM_DROPPED.get(Items.STICK), 123456);
        stats.put(Stats.ENTITY_KILLED.get(EntityTypes.ZOMBIE), 7);
        stats.put(Stats.ENTITY_KILLED_BY.get(EntityTypes.SKELETON), -99);
        stats.put(Stats.CUSTOM.get(Stats.PLAY_TIME), 42);
        return stats;
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            Object2IntMap<Stat<?>> expected,
            int expectedSize) {
        ClientboundAwardStatsPacket original = new ClientboundAwardStatsPacket(expected);
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, original);
            ByteBuf wireView = encoded.duplicate();
            assertEquals(3, readVarInt(wireView));
            assertEquals(expectedSize, readVarInt(wireView));

            Packet<?> decoded = decode(protocol, encoded);
            ClientboundAwardStatsPacket awardStats = assertInstanceOf(
                    ClientboundAwardStatsPacket.class, decoded);
            assertFalse(encoded.isReadable());
            assertEquals(expectedSize, awardStats.stats().size());
            for (Object2IntMap.Entry<Stat<?>> entry : expected.object2IntEntrySet()) {
                assertTrue(awardStats.stats().containsKey(entry.getKey()));
                assertEquals(
                        entry.getIntValue(), awardStats.stats().getInt(entry.getKey()));
            }
        } finally {
            encoded.release();
        }
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
                3,
                PacketDisposition.STATEFUL,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, 3, payload);
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("viewer-local award_stats must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local award_stats must not decode");
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
