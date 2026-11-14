package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Lifecycle;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
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
import io.papermc.paper.configuration.GlobalConfiguration;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
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

final class Id108SetPlayerInventoryPacketDefinitionTest {

    private static final int PACKET_ID = 108;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_player_inventory";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000181");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000182");
    private static final Map<Item, Object> originalItemComponents = new HashMap<>();
    private static Object originalServer;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        bindTestItemComponents();
        installTestGlobalConfiguration();
        installTestMinecraftServerForItemSanitizer();
    }

    @AfterAll
    static void restoreItemComponents() {
        try {
            Field serverField = MinecraftServer.class.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            serverField.set(null, originalServer);
            for (var entry : originalItemComponents.entrySet()) {
                Field componentsField = entry.getKey().builtInRegistryHolder().getClass()
                        .getDeclaredField("components");
                componentsField.setAccessible(true);
                componentsField.set(entry.getKey().builtInRegistryHolder(), entry.getValue());
            }
            originalItemComponents.clear();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not restore test item components", failure);
        }
    }

    @Test
    void classifiesDirectInventorySlotUpdatesAsCapturedViewerLocalState() {
        var definition = new Id108SetPlayerInventoryPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x6C, Id108SetPlayerInventoryPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_player_inventory",
                Id108SetPlayerInventoryPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id108SetPlayerInventoryPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id108SetPlayerInventoryPacketDefinition.class,
                catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
    }

    @Test
    void nativeCodecPreservesValidInventorySlotsAndUnvalidatedSignedSlotValues() {
        ProtocolInfo<?> protocol = protocol();
        int[] slots = {0, 8, 9, 35, 36, 39, 40, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};

        for (int slot : slots) {
            assertNativeRoundTrip(protocol, packet(slot, ItemStack.EMPTY));
        }
    }

    @Test
    void nativeWireOrderIsPacketIdThenSlotVarIntThenOptionalItemStack() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundSetPlayerInventoryPacket expected = packet(17, namedDamagedSword());
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(17, fields.readVarInt());
            assertEquals(expected.contents().getCount(), fields.readVarInt());
            assertEquals(BuiltInRegistries.ITEM.getId(Items.DIAMOND_SWORD), fields.readVarInt());
            assertTrue(fields.isReadable(), "ItemStack data-component patch follows its item ID");
            assertEquals(GamePacketTypes.CLIENTBOUND_SET_PLAYER_INVENTORY, expected.type());

            ClientboundSetPlayerInventoryPacket decoded = assertInstanceOf(
                    ClientboundSetPlayerInventoryPacket.class, decode(protocol, encoded));
            assertEquals(17, decoded.slot());
            assertTrue(ItemStack.matches(expected.contents(), decoded.contents()));
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecRoundTripsEmptyAndComponentBearingItemStacks() {
        ProtocolInfo<?> protocol = protocol();

        assertNativeRoundTrip(protocol, packet(0, ItemStack.EMPTY));
        assertNativeRoundTrip(protocol, packet(40, new ItemStack(Items.IRON_INGOT, 64)));
        assertNativeRoundTrip(protocol, packet(8, namedDamagedSword()));
        assertNativeRoundTrip(protocol, packet(9, itemWithRemovedDefaultComponent()));
    }

    @Test
    void nativeCodecPreservesRemovedItemDataComponents() {
        ItemStack stack = itemWithRemovedDefaultComponent();

        assertTrue(stack.getComponentsPatch().entrySet().stream().anyMatch(entry ->
                entry.getKey() == DataComponents.MAX_STACK_SIZE && entry.getValue().isEmpty()));
        assertNativeRoundTrip(protocol(), packet(9, stack));
    }

    @Test
    void nativeDecoderTreatsNonpositiveItemCountsAsEmpty() {
        ProtocolInfo<?> protocol = protocol();

        for (int count : new int[] {0, -1, Integer.MIN_VALUE}) {
            ByteBuf wire = Unpooled.buffer();
            try {
                VarInt.write(wire, PACKET_ID);
                VarInt.write(wire, 12);
                VarInt.write(wire, count);

                ClientboundSetPlayerInventoryPacket decoded = assertInstanceOf(
                        ClientboundSetPlayerInventoryPacket.class, decode(protocol, wire));
                assertEquals(12, decoded.slot());
                assertTrue(decoded.contents().isEmpty());
                assertFalse(wire.isReadable());
            } finally {
                wire.release();
            }
        }
    }

    @Test
    void nativeDecoderRejectsEveryTruncatedPayloadPrefixAndMalformedVarInts() {
        ProtocolInfo<?> protocol = protocol();
        byte[] completeBody = body(protocol, packet(300, namedDamagedSword()));
        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, Arrays.copyOf(completeBody, length));
        }

        assertNativeDecodeFails(protocol, new byte[] {(byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {0, (byte) 0x80});
        assertNativeDecodeFails(protocol, new byte[] {
            (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        });
    }

    @Test
    void nativeDecoderLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(protocol, packet(23, namedDamagedSword()));
        try {
            wire.writeByte(0x7A);
            ClientboundSetPlayerInventoryPacket decoded = assertInstanceOf(
                    ClientboundSetPlayerInventoryPacket.class, decode(protocol, wire));

            assertEquals(23, decoded.slot());
            assertTrue(ItemStack.matches(namedDamagedSword(), decoded.contents()));
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void playbackDropsValidAndMalformedPayloadsBeforeCodecAccess() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id108SetPlayerInventoryPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                body(protocol, packet(4, ItemStack.EMPTY)),
                body(protocol, packet(36, namedDamagedSword())),
                new byte[0],
                new byte[] {(byte) 0x80},
                new byte[] {0, (byte) 0x80},
                new byte[] {8, 1})) {
            PacketRewriteResult result = definition.handlePlayback(
                    frame(payload), descriptor(), codec, rewriter());
            assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
            assertTrue(result.packet().isEmpty());
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingTheDecodedPacket() {
        PacketRewriteResult result = new Id108SetPlayerInventoryPacketDefinition().rewrite(
                packet(7, namedDamagedSword()), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id108SetPlayerInventoryPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, true, PacketDescriptor.PlaybackScope.SAFE),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.CONFIGURATION,
                        PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                        PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.UNSAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
        }

        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundSetPlayerInventoryPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] expectedWire = bytes(encoded);
            ClientboundSetPlayerInventoryPacket decoded = assertInstanceOf(
                    ClientboundSetPlayerInventoryPacket.class, decode(protocol, encoded));
            assertEquals(expected.slot(), decoded.slot());
            if (expected.contents().isEmpty()) {
                assertTrue(decoded.contents().isEmpty());
            } else {
                assertTrue(ItemStack.matches(expected.contents(), decoded.contents()));
            }
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(expectedWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static ClientboundSetPlayerInventoryPacket packet(int slot, ItemStack contents) {
        return new ClientboundSetPlayerInventoryPacket(slot, contents);
    }

    private static ItemStack namedDamagedSword() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Replay item: 剣"));
        sword.set(DataComponents.DAMAGE, 13);
        return sword;
    }

    private static ItemStack itemWithRemovedDefaultComponent() {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        stack.remove(DataComponents.MAX_STACK_SIZE);
        return stack;
    }

    private static void bindTestItemComponents() {
        try {
            for (Item item : List.of(Items.DIAMOND_SWORD, Items.IRON_INGOT)) {
                Object holder = item.builtInRegistryHolder();
                Field componentsField = holder.getClass().getDeclaredField("components");
                componentsField.setAccessible(true);
                originalItemComponents.put(item, componentsField.get(holder));
                DataComponentMap components = item == Items.IRON_INGOT
                        ? DataComponentMap.builder()
                                .set(DataComponents.MAX_STACK_SIZE, 64)
                                .build()
                        : DataComponentMap.EMPTY;
                item.builtInRegistryHolder().bindComponents(components);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not bind test item components", failure);
        }
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

    private static void installTestMinecraftServerForItemSanitizer() {
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

    private static byte[] body(
            ProtocolInfo<?> protocol, ClientboundSetPlayerInventoryPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            return bytes(fields);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf encodePacket(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            encode(protocol, buffer, packet);
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

    private static void assertNativeDecodeFails(ProtocolInfo<?> protocol, byte[] body) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(body);
            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
        }
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
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        return descriptor(typeName, packetId, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, replayable, scope);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            boolean replayable,
            PacketDescriptor.PlaybackScope scope) {
        boolean clientbound = direction == PacketDescriptor.Direction.CLIENTBOUND;
        return new PacketDescriptor(
                typeName,
                phase,
                direction,
                packetId,
                clientbound ? PacketDisposition.STATEFUL : PacketDisposition.CONTROL,
                clientbound,
                clientbound && replayable,
                false,
                "paper-26.2/" + typeName,
                clientbound ? scope : PacketDescriptor.PlaybackScope.UNSAFE);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local inventory must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local inventory must not be decoded");
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
