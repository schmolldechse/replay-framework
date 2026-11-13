package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id017To020ContainerPacketDefinitionsTest {

    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        installTestGlobalConfiguration();
    }

    @Test
    void classifiesEveryContainerPacketAsViewerLocalState() {
        assertConstants();
        for (PacketCase packetCase : packetCases()) {
            PacketDefinition<Paper26_2ReplayPacketRewriter> definition =
                    packetCase.definition();
            assertEquals(packetCase.packetId(), definition.packetId());
            assertEquals(packetCase.typeName(), definition.typeName());
            assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
            assertTrue(definition.classification().captureByDefault());
            assertTrue(definition.classification().replayable());
            assertFalse(definition.classification().checkpointRelevant());
            assertEquals(
                    PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                    definition.classification().playbackScope());
        }
    }

    @Test
    void dropsEveryRecordedPayloadBeforeDecodeOrEncode() {
        for (PacketCase packetCase : packetCases()) {
            AtomicInteger decodes = new AtomicInteger();
            AtomicInteger encodes = new AtomicInteger();
            PacketCodec<Object> codec = failingCodec(packetCase.identifier(), decodes, encodes);

            for (byte[] payload : List.of(
                    new byte[0],
                    new byte[] {0},
                    new byte[] {(byte) 0x80},
                    new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                            (byte) 0xFF, (byte) 0xFF, 1},
                    new byte[] {1, 2, 3, 4, 5, 6, 7, 8})) {
                PacketRewriteResult result = packetCase.definition().handlePlayback(
                        frame(packetCase.packetId(), payload),
                        descriptor(packetCase),
                        codec,
                        rewriter());

                assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
                assertTrue(result.packet().isEmpty());
                assertEquals(
                        "recorded connection state does not belong to the observer",
                        result.reason());
            }

            assertEquals(0, decodes.get());
            assertEquals(0, encodes.get());
        }
    }

    @Test
    void directRewriteDropsWithoutInspectingDecodedType() {
        for (PacketCase packetCase : packetCases()) {
            PacketRewriteResult result = packetCase.definition().rewrite(
                    new Object(), descriptor(packetCase), rewriter());

            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }
    }

    @Test
    void rejectsDescriptorMismatchesAndNullBoundaries() {
        for (PacketCase packetCase : packetCases()) {
            PacketCodec<Object> codec = failingCodec(
                    packetCase.identifier(), new AtomicInteger(), new AtomicInteger());
            PacketDescriptor valid = descriptor(packetCase);
            List<PacketDescriptor> mismatches = List.of(
                    descriptor(packetCase, "play.clientbound.minecraft:other",
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId() + 1, PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.CONFIGURATION,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.SERVERBOUND, false,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, false,
                            PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                    descriptor(packetCase, packetCase.typeName(),
                            packetCase.packetId(), PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND, true,
                            PacketDescriptor.PlaybackScope.SAFE));

            for (PacketDescriptor mismatch : mismatches) {
                assertThrows(IncompatibleAdapterException.class, () ->
                        packetCase.definition().handlePlayback(
                                frame(packetCase.packetId(), new byte[0]),
                                mismatch,
                                codec,
                                rewriter()));
            }

            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(null, valid, codec, rewriter()));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(
                            frame(packetCase.packetId(), new byte[0]), null, codec, rewriter()));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(
                            frame(packetCase.packetId(), new byte[0]), valid, null, rewriter()));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().handlePlayback(
                            frame(packetCase.packetId(), new byte[0]), valid, codec, null));
            assertThrows(NullPointerException.class, () ->
                    packetCase.definition().rewrite(null, valid, rewriter()));
        }
    }

    @Test
    void nativeCloseCodecCoversContainerVarIntsAndFraming() {
        ProtocolInfo<?> protocol = protocol();
        for (int containerId : new int[] {
                0, 1, 127, 128, 16_384, Integer.MAX_VALUE, Integer.MIN_VALUE
        }) {
            ClientboundContainerClosePacket decoded = roundTrip(
                    protocol,
                    new ClientboundContainerClosePacket(containerId),
                    0x11,
                    ClientboundContainerClosePacket.class);
            assertEquals(containerId, decoded.getContainerId());
        }

        assertMalformed(protocol, 0x11, new byte[0]);
        assertMalformed(protocol, 0x11, new byte[] {(byte) 0x80});
        assertMalformed(protocol, 0x11, new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1
        });
        assertLeavesTrailingByte(
                protocol, new ClientboundContainerClosePacket(4),
                ClientboundContainerClosePacket.class);
    }

    @Test
    void nativeContentCodecPreservesPositionsCursorAndComponents() {
        ProtocolInfo<?> protocol = protocol();
        ItemStack namedDiamond = namedStack();
        ClientboundContainerSetContentPacket componentBearing =
                new ClientboundContainerSetContentPacket(
                        128,
                        16_384,
                        List.of(ItemStack.EMPTY, stack(Items.STONE, 64), namedDiamond),
                        stack(Items.APPLE, 2));
        assertEquals(3, componentBearing.items().size());
        assertTrue(componentBearing.items().get(0).isEmpty());
        assertTrue(ItemStack.matches(namedDiamond, componentBearing.items().get(2)));
        assertEquals("Recorded diamond", componentBearing.items().get(2).getHoverName().getString());
        assertEquals(2, componentBearing.carriedItem().getCount());

        List<ClientboundContainerSetContentPacket> packets = List.of(
                new ClientboundContainerSetContentPacket(
                        0, 0, List.of(), ItemStack.EMPTY),
                new ClientboundContainerSetContentPacket(
                        128,
                        16_384,
                        List.of(ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY),
                        ItemStack.EMPTY),
                new ClientboundContainerSetContentPacket(
                        Integer.MIN_VALUE,
                        Integer.MAX_VALUE,
                        List.of(ItemStack.EMPTY, ItemStack.EMPTY),
                        ItemStack.EMPTY));

        for (ClientboundContainerSetContentPacket packet : packets) {
            ClientboundContainerSetContentPacket decoded = roundTrip(
                    protocol, packet, 0x12, ClientboundContainerSetContentPacket.class);
            assertEquals(packet.containerId(), decoded.containerId());
            assertEquals(packet.stateId(), decoded.stateId());
            assertTrue(ItemStack.listMatches(packet.items(), decoded.items()));
            assertTrue(ItemStack.matches(packet.carriedItem(), decoded.carriedItem()));
        }
    }

    @Test
    void nativeContentCodecExposesPerSlotLargePacketFallback() {
        ItemStack namedDiamond = namedStack();
        List<ItemStack> items = List.of(
                ItemStack.EMPTY, stack(Items.STONE, 7), namedDiamond);
        ClientboundContainerSetContentPacket content =
                new ClientboundContainerSetContentPacket(
                        12, 34, items, stack(Items.APPLE, 1));
        RecordingConnection connection = new RecordingConnection();

        assertTrue(content.hasLargePacketFallback());
        assertTrue(content.packetTooLarge(connection));
        assertEquals(items.size(), connection.sent.size());
        for (int index = 0; index < items.size(); index++) {
            ClientboundContainerSetSlotPacket slot = assertInstanceOf(
                    ClientboundContainerSetSlotPacket.class, connection.sent.get(index));
            assertEquals(12, slot.getContainerId());
            assertEquals(34, slot.getStateId());
            assertEquals(index, slot.getSlot());
            assertTrue(ItemStack.matches(items.get(index), slot.getItem()));
        }
    }

    @Test
    void nativeContentCodecRejectsIncompleteListsAndStacks() {
        ProtocolInfo<?> protocol = protocol();
        assertMalformed(protocol, 0x12, new byte[0]);

        ByteBuf negativeList = Unpooled.buffer();
        try {
            VarInt.write(negativeList, 0x12);
            VarInt.write(negativeList, 1);
            VarInt.write(negativeList, 2);
            VarInt.write(negativeList, -1);
            assertThrows(RuntimeException.class, () -> decode(protocol, negativeList));
        } finally {
            negativeList.release();
        }

        assertTruncatedPacket(protocol, new ClientboundContainerSetContentPacket(
                2, 3, List.of(ItemStack.EMPTY), ItemStack.EMPTY));
        assertLeavesTrailingByte(
                protocol,
                new ClientboundContainerSetContentPacket(
                        2, 3, List.of(ItemStack.EMPTY), ItemStack.EMPTY),
                ClientboundContainerSetContentPacket.class);
    }

    @Test
    void nativeDataCodecCoversSignedShortsAndCanonicalization() {
        ProtocolInfo<?> protocol = protocol();
        for (int[] values : List.of(
                new int[] {0, 0, 0},
                new int[] {128, 1, 200},
                new int[] {16_384, Short.MIN_VALUE, Short.MAX_VALUE},
                new int[] {Integer.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE},
                new int[] {Integer.MIN_VALUE, -7, -300})) {
            ClientboundContainerSetDataPacket decoded = roundTrip(
                    protocol,
                    new ClientboundContainerSetDataPacket(values[0], values[1], values[2]),
                    0x13,
                    ClientboundContainerSetDataPacket.class);
            assertEquals(values[0], decoded.getContainerId());
            assertEquals(values[1], decoded.getId());
            assertEquals(values[2], decoded.getValue());
        }

        ClientboundContainerSetDataPacket canonical = decodePacket(
                protocol, new ClientboundContainerSetDataPacket(9, 65_535, 65_536),
                ClientboundContainerSetDataPacket.class);
        assertEquals(-1, canonical.getId());
        assertEquals(0, canonical.getValue());

        assertMalformed(protocol, 0x13, new byte[] {1});
        assertMalformed(protocol, 0x13, new byte[] {1, 0});
        assertMalformed(protocol, 0x13, new byte[] {1, 0, 0, 0});
        assertLeavesTrailingByte(
                protocol, new ClientboundContainerSetDataPacket(3, 2, 1),
                ClientboundContainerSetDataPacket.class);
    }

    @Test
    void nativeSlotCodecCoversSignedSlotsStacksAndDefensiveCopy() {
        ProtocolInfo<?> protocol = protocol();
        ItemStack source = namedStack();
        ClientboundContainerSetSlotPacket copied =
                new ClientboundContainerSetSlotPacket(4, 5, 6, source);
        assertNotSame(source, copied.getItem());
        assertTrue(ItemStack.matches(source, copied.getItem()));

        List<ClientboundContainerSetSlotPacket> packets = List.of(
                new ClientboundContainerSetSlotPacket(0, 0, 0, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(
                        128, 16_384, 12, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(
                        Integer.MIN_VALUE, Integer.MAX_VALUE, Short.MIN_VALUE, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(
                        Integer.MAX_VALUE, Integer.MIN_VALUE, Short.MAX_VALUE,
                        ItemStack.EMPTY));

        for (ClientboundContainerSetSlotPacket packet : packets) {
            ClientboundContainerSetSlotPacket decoded = roundTrip(
                    protocol, packet, 0x14, ClientboundContainerSetSlotPacket.class);
            assertEquals(packet.getContainerId(), decoded.getContainerId());
            assertEquals(packet.getStateId(), decoded.getStateId());
            assertEquals(packet.getSlot(), decoded.getSlot());
            assertTrue(ItemStack.matches(packet.getItem(), decoded.getItem()));
        }

        assertMalformed(protocol, 0x14, new byte[] {1});
        assertMalformed(protocol, 0x14, new byte[] {1, 1, 0});
        assertTruncatedPacket(protocol, new ClientboundContainerSetSlotPacket(
                2, 3, 4, ItemStack.EMPTY));
        assertLeavesTrailingByte(
                protocol,
                new ClientboundContainerSetSlotPacket(2, 3, 4, ItemStack.EMPTY),
                ClientboundContainerSetSlotPacket.class);
    }

    private static void assertConstants() {
        assertEquals(0x11, Id017ContainerClosePacketDefinition.PACKET_ID);
        assertEquals("minecraft:container_close", Id017ContainerClosePacketDefinition.IDENTIFIER);
        assertEquals(0x12, Id018ContainerSetContentPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:container_set_content",
                Id018ContainerSetContentPacketDefinition.IDENTIFIER);
        assertEquals(0x13, Id019ContainerSetDataPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:container_set_data",
                Id019ContainerSetDataPacketDefinition.IDENTIFIER);
        assertEquals(0x14, Id020ContainerSetSlotPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:container_set_slot",
                Id020ContainerSetSlotPacketDefinition.IDENTIFIER);
    }

    private static ItemStack namedStack() {
        ItemStack stack = stack(Items.DIAMOND, 3);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Recorded diamond"));
        return stack;
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        var holder = item.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.EMPTY);
        }
        return new ItemStack(holder, count);
    }

    private static void installTestGlobalConfiguration() {
        if (GlobalConfiguration.get() != null) {
            return;
        }
        try {
            GlobalConfiguration configuration = new GlobalConfiguration();
            configuration.misc = configuration.new Misc();
            configuration.packetLimiter = configuration.new PacketLimiter();
            configuration.packetLimiter.allPackets =
                    new GlobalConfiguration.PacketLimiter.PacketLimit(
                            0.0D,
                            0.0D,
                            GlobalConfiguration.PacketLimiter.PacketLimit.ViolateAction.DROP);
            configuration.packetLimiter.overrides = Map.of();
            Method setter = GlobalConfiguration.class.getDeclaredMethod(
                    "set", GlobalConfiguration.class);
            setter.setAccessible(true);
            setter.invoke(null, configuration);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not initialize Paper test configuration", failure);
        }
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static <T extends Packet<?>> T roundTrip(
            ProtocolInfo<?> protocol,
            T packet,
            int expectedPacketId,
            Class<T> packetType) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            byte[] expectedWire = bytes(encoded);
            ByteBuf wireView = encoded.duplicate();
            assertEquals(expectedPacketId, VarInt.read(wireView));

            T decoded = packetType.cast(decode(protocol, encoded));
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encode(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static <T extends Packet<?>> T decodePacket(
            ProtocolInfo<?> protocol, T packet, Class<T> packetType) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            return packetType.cast(decode(protocol, encoded));
        } finally {
            encoded.release();
        }
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, int packetId, byte[] payload) {
        ByteBuf malformed = Unpooled.buffer();
        try {
            VarInt.write(malformed, packetId);
            malformed.writeBytes(payload);
            assertThrows(RuntimeException.class, () -> decode(protocol, malformed));
        } finally {
            malformed.release();
        }
    }

    private static void assertTruncatedPacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = encode(protocol, packet);
        ByteBuf truncated = encoded.copy(encoded.readerIndex(), encoded.readableBytes() - 1);
        try {
            assertThrows(RuntimeException.class, () -> decode(protocol, truncated));
        } finally {
            truncated.release();
            encoded.release();
        }
    }

    private static void assertLeavesTrailingByte(
            ProtocolInfo<?> protocol, Packet<?> packet, Class<? extends Packet<?>> packetType) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(packetType, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static ByteBuf encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        encode(protocol, buffer, packet);
        return buffer;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encode(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of(999));
    }

    private static PacketDescriptor descriptor(PacketCase packetCase) {
        return descriptor(
                packetCase,
                packetCase.typeName(),
                packetCase.packetId(),
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            PacketCase packetCase,
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
                replayable,
                false,
                "paper-26.2/" + packetCase.identifier(),
                scope);
    }

    private static RawPacketFrame frame(int packetId, byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, packetId, payload);
    }

    private static PacketCodec<Object> failingCodec(
            String identifier,
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local " + identifier + " must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local " + identifier + " must not decode");
            }
        };
    }

    private static List<PacketCase> packetCases() {
        return List.of(
                new PacketCase(
                        17, "minecraft:container_close",
                        new Id017ContainerClosePacketDefinition()),
                new PacketCase(
                        18, "minecraft:container_set_content",
                        new Id018ContainerSetContentPacketDefinition()),
                new PacketCase(
                        19, "minecraft:container_set_data",
                        new Id019ContainerSetDataPacketDefinition()),
                new PacketCase(
                        20, "minecraft:container_set_slot",
                        new Id020ContainerSetSlotPacketDefinition()));
    }

    private record PacketCase(
            int packetId,
            String identifier,
            PacketDefinition<Paper26_2ReplayPacketRewriter> definition) {

        String typeName() {
            return "play.clientbound." + identifier;
        }
    }

    private static final class RecordingConnection extends Connection {

        private final List<Packet<?>> sent = new ArrayList<>();

        private RecordingConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            sent.add(packet);
        }
    }
}
