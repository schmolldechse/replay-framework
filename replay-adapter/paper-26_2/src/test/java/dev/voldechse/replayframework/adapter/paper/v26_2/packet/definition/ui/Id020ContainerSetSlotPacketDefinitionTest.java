package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.paper.packet.PacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.Paper26_2PacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id020ContainerSetSlotPacketDefinitionTest {

    private static final int PACKET_ID = 20;
    private static final String IDENTIFIER = "minecraft:container_set_slot";
    private static final String TYPE_NAME = "play.clientbound.minecraft:container_set_slot";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static Object originalServer;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        installTestGlobalConfiguration();
        installTestMinecraftServerForPaperItemSanitizer();
    }

    @AfterAll
    static void restoreMinecraftServerSingleton() {
        try {
            Field serverField = MinecraftServer.class.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            serverField.set(null, originalServer);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not restore Paper test server singleton", failure);
        }
    }

    @Test
    void classifiesAndRegistersOnlyContainerSetSlot() {
        var definition = new Id020ContainerSetSlotPacketDefinition();
        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x14, Id020ContainerSetSlotPacketDefinition.PACKET_ID);
        assertEquals(IDENTIFIER, Id020ContainerSetSlotPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        assertEquals(1, UiPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName())).count());
        assertEquals(1, Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName())).count());
        var registered = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, registered.typeName());
        assertEquals(PacketDisposition.STATEFUL, registered.disposition());
        assertTrue(registered.captureByDefault());
        assertTrue(registered.replayable());
        assertFalse(registered.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, registered.playbackScope());
    }

    @Test
    void dropsRecordedSlotUpdatesBeforeDecodeAndRejectsDescriptorMismatches() {
        PacketDefinition<Paper26_2ReplayPacketRewriter> definition =
                new Id020ContainerSetSlotPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {0},
                new byte[] {(byte) 0x80},
                new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                        (byte) 0xFF, (byte) 0xFF, 1},
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8})) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }
        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL,
                definition.rewrite(new Object(), descriptor(), rewriter()).decision());

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(TYPE_NAME, PACKET_ID + 1, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                codec,
                rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE),
                codec,
                rewriter()));
        assertThrows(NullPointerException.class, () ->
                definition.handlePlayback(null, descriptor(), codec, rewriter()));
    }

    @Test
    void nativeCodecCoversSignedSlotsStacksAndDefensiveCopy() {
        ProtocolInfo<?> protocol = protocol();
        ItemStack source = namedStack();
        ClientboundContainerSetSlotPacket copied =
                new ClientboundContainerSetSlotPacket(4, 5, 6, source);
        assertNotSame(source, copied.getItem());
        assertTrue(ItemStack.matches(source, copied.getItem()));

        List<ClientboundContainerSetSlotPacket> packets = List.of(
                new ClientboundContainerSetSlotPacket(0, 0, 0, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(128, 16_384, 12, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(
                        Integer.MIN_VALUE, Integer.MAX_VALUE, Short.MIN_VALUE, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(
                        Integer.MAX_VALUE, Integer.MIN_VALUE, Short.MAX_VALUE, ItemStack.EMPTY),
                new ClientboundContainerSetSlotPacket(9, 10, -1, source));

        for (ClientboundContainerSetSlotPacket packet : packets) {
            ClientboundContainerSetSlotPacket decoded = roundTrip(protocol, packet);
            assertEquals(packet.getContainerId(), decoded.getContainerId());
            assertEquals(packet.getStateId(), decoded.getStateId());
            assertEquals(packet.getSlot(), decoded.getSlot());
            assertTrue(ItemStack.matches(packet.getItem(), decoded.getItem()));
        }

        assertMalformed(protocol, new byte[] {1});
        assertMalformed(protocol, new byte[] {1, 1, 0});
        assertTruncatedPacket(protocol,
                new ClientboundContainerSetSlotPacket(2, 3, 4, ItemStack.EMPTY));
        assertLeavesTrailingByte(protocol,
                new ClientboundContainerSetSlotPacket(2, 3, 4, ItemStack.EMPTY));
    }

    private static ClientboundContainerSetSlotPacket roundTrip(
            ProtocolInfo<?> protocol, ClientboundContainerSetSlotPacket packet) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            byte[] expectedWire = bytes(encoded);
            assertEquals(PACKET_ID, VarInt.read(encoded.duplicate()));
            ClientboundContainerSetSlotPacket decoded =
                    (ClientboundContainerSetSlotPacket) decode(protocol, encoded);
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

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf malformed = Unpooled.buffer();
        try {
            VarInt.write(malformed, PACKET_ID);
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
            ProtocolInfo<?> protocol, ClientboundContainerSetSlotPacket packet) {
        ByteBuf encoded = encode(protocol, packet);
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(ClientboundContainerSetSlotPacket.class, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static ItemStack namedStack() {
        ItemStack stack = stack(Items.DIAMOND, 3);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Recorded diamond"));
        return stack;
    }

    private static ItemStack stack(Item item, int count) {
        var holder = item.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.EMPTY);
        }
        return new ItemStack(holder, count);
    }

    private static ByteBuf encode(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            encodeInto(protocol, buffer, packet);
            return buffer;
        } catch (RuntimeException | Error failure) {
            buffer.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeInto(ProtocolInfo<?> protocol, ByteBuf buffer, Packet<?> packet) {
        ((StreamCodec) protocol.codec()).encode(buffer, packet);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
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

    private static void installTestMinecraftServerForPaperItemSanitizer() {
        try {
            Field serverField = MinecraftServer.class.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            originalServer = serverField.get(null);
            if (originalServer != null) {
                return;
            }

            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Object testServer = unsafeType
                    .getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, DedicatedServer.class);

            Field registriesField = MinecraftServer.class.getDeclaredField("registries");
            registriesField.setAccessible(true);
            registriesField.set(testServer, testRegistryAccess());
            serverField.set(null, testServer);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "could not provide Paper's item sanitizer with test registries", failure);
        }
    }

    // Paper's outbound ItemStack codec resolves component registries through the active server.
    private static LayeredRegistryAccess<RegistryLayer> testRegistryAccess() {
        MappedRegistry<Enchantment> enchantments = new MappedRegistry<>(
                Registries.ENCHANTMENT, Lifecycle.stable());
        var enchantmentDefinition = Enchantment.definition(
                HolderSet.direct(Items.DIAMOND_SWORD.builtInRegistryHolder()),
                1,
                1,
                Enchantment.constantCost(1),
                Enchantment.constantCost(1),
                1,
                EquipmentSlotGroup.MAINHAND);
        enchantments.register(
                ResourceKey.create(
                        Registries.ENCHANTMENT, Identifier.parse("replay:test_enchantment")),
                new Enchantment(
                        Component.literal("Test enchantment"),
                        enchantmentDefinition,
                        HolderSet.empty(),
                        DataComponentMap.EMPTY),
                RegistrationInfo.BUILT_IN);
        enchantments.freeze();

        Map<ResourceKey<? extends net.minecraft.core.Registry<?>>,
                net.minecraft.core.Registry<?>> registries = new HashMap<>();
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                .registries()
                .forEach(entry -> registries.put(entry.key(), entry.value()));
        registries.put(Registries.ENCHANTMENT, enchantments);
        RegistryAccess.Frozen registryAccess =
                new RegistryAccess.ImmutableRegistryAccess(registries).freeze();

        return new LayeredRegistryAccess<>(List.of(
                        RegistryLayer.STATIC,
                        RegistryLayer.WORLDGEN,
                        RegistryLayer.DIMENSIONS,
                        RegistryLayer.RELOADABLE))
                .replaceFrom(
                        RegistryLayer.STATIC,
                        List.of(
                                registryAccess,
                                RegistryAccess.EMPTY,
                                RegistryAccess.EMPTY,
                                RegistryAccess.EMPTY));
    }

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName, int packetId, PacketPhase phase,
            PacketDescriptor.Direction direction, boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(typeName, phase, direction, packetId,
                PacketDisposition.STATEFUL,
                direction == PacketDescriptor.Direction.CLIENTBOUND,
                replayable, false, "paper-26.2/" + IDENTIFIER, scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID), Set.of(999));
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local container slot must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local container slot must not decode");
            }
        };
    }
}
