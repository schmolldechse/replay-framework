package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id052MerchantOffersPacketDefinitionTest {

    private static final int PACKET_ID = 52;
    private static final String TYPE_NAME = "play.clientbound.minecraft:merchant_offers";
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
    void classifiesMerchantOffersAsCapturedViewerLocalState() {
        var definition = new Id052MerchantOffersPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x34, Id052MerchantOffersPacketDefinition.PACKET_ID);
        assertEquals("minecraft:merchant_offers", Id052MerchantOffersPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInTheUiDomainCatalogAndPaperRegistry() {
        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id052MerchantOffersPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id052MerchantOffersPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void dropsCapturedRawFramesBeforeDecodeOrEncode() {
        var definition = new Id052MerchantOffersPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        byte[] encoded = encodePacketBytes(protocol(), packet(5, new MerchantOffers(), 2, 30, true, true));
        byte[] validPayload = Arrays.copyOfRange(encoded, 1, encoded.length);

        for (byte[] payload : new byte[][] {
            new byte[0],
            new byte[] {0},
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xFF, (byte) 0x80, 0},
            Arrays.copyOf(validPayload, validPayload.length - 1),
            validPayload
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
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsWithoutInspectingTheDecodedPacketType() {
        PacketRewriteResult result = new Id052MerchantOffersPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id052MerchantOffersPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeCodecPreservesIntegerBoundariesAndBothPacketFlags() {
        ProtocolInfo<?> protocol = protocol();
        int[] values = {
            Integer.MIN_VALUE, -1, 0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE
        };

        for (int value : values) {
            assertNativeRoundTrip(protocol, packet(value, new MerchantOffers(), 2, 30, false, true));
            assertNativeRoundTrip(protocol, packet(5, new MerchantOffers(), value, value, true, false));
        }
        for (boolean showProgress : new boolean[] {false, true}) {
            for (boolean canRestock : new boolean[] {false, true}) {
                assertNativeRoundTrip(protocol, packet(
                        5, new MerchantOffers(), 2, 30, showProgress, canRestock));
            }
        }
    }

    @Test
    void nativeCodecPreservesEmptySingleAndVarIntLengthOfferLists() {
        ProtocolInfo<?> protocol = protocol();
        assertNativeRoundTrip(protocol, packet(1, new MerchantOffers(), 1, 0, false, false));

        MerchantOffers oneOffer = new MerchantOffers();
        oneOffer.add(offer(
                cost(Items.EMERALD, 4),
                Optional.empty(),
                namedStack(Items.BOOK, 1, "Single offer"),
                0,
                12,
                2,
                0.05F,
                -3,
                Integer.MIN_VALUE));
        assertNativeRoundTrip(protocol, packet(2, oneOffer, 1, 5, true, false));

        MerchantOffers manyOffers = new MerchantOffers();
        for (int index = 0; index < 128; index++) {
            manyOffers.add(offer(
                    cost(Items.EMERALD, 1 + index % 64),
                    index % 2 == 0
                            ? Optional.of(cost(Items.BOOK, 1))
                            : Optional.empty(),
                    stack(Items.DIAMOND, 1 + index % 16),
                    index % 5,
                    16,
                    index,
                    index % 2 == 0 ? 0.0F : -0.25F,
                    index - 64,
                    index * 3));
        }
        assertNativeRoundTrip(protocol, packet(3, manyOffers, 5, 999, true, true));
    }

    @Test
    void nativeCodecPreservesOfferCostsComponentsResultsAndAllExposedProperties() {
        ProtocolInfo<?> protocol = protocol();
        ItemCost namedEmeraldCost = cost(Items.EMERALD, 7)
                .withComponents(builder -> builder.expect(
                        DataComponents.CUSTOM_NAME, Component.literal("Marked emeralds")));
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer(
                namedEmeraldCost,
                Optional.empty(),
                namedStack(Items.ENCHANTED_BOOK, 1, "Mending book"),
                0,
                12,
                3,
                0.05F,
                -12,
                Integer.MIN_VALUE,
                false));
        offers.add(offer(
                cost(Items.EMERALD, 32),
                Optional.of(cost(Items.BOOK, 2)),
                namedStack(Items.DIAMOND_SWORD, 1, "Trade reward"),
                11,
                11,
                Integer.MAX_VALUE,
                Float.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE));
        offers.add(offer(
                cost(Items.WHEAT, 64),
                Optional.of(cost(Items.CARROT, 64)),
                namedStack(Items.APPLE, 64, "Boundary reward"),
                Integer.MIN_VALUE,
                0,
                -1,
                -Float.MAX_VALUE,
                Integer.MIN_VALUE,
                0));

        assertNativeRoundTrip(protocol, packet(8, offers, 5, Integer.MAX_VALUE, false, true));
    }

    @Test
    void nativeCodecRejectsEveryTruncatedPrefixAndLeavesTrailingFrameBytes() {
        ProtocolInfo<?> protocol = protocol();
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer(
                cost(Items.EMERALD, 5),
                Optional.of(cost(Items.BOOK, 1)),
                namedStack(Items.DIAMOND, 2, "Truncation sample"),
                2,
                16,
                3,
                0.05F,
                -1,
                7));
        byte[] encoded = encodePacketBytes(protocol, packet(127, offers, 4, 321, true, true));

        for (int length = 0; length < encoded.length; length++) {
            ByteBuf truncated = Unpooled.wrappedBuffer(Arrays.copyOf(encoded, length));
            try {
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated),
                        "truncated length " + length);
            } finally {
                truncated.release();
            }
        }

        ByteBuf withTrailingByte = Unpooled.buffer();
        try {
            withTrailingByte.writeBytes(encoded);
            withTrailingByte.writeByte(0x7A);

            ClientboundMerchantOffersPacket expected = packet(127, offers, 4, 321, true, true);
            ClientboundMerchantOffersPacket actual = assertInstanceOf(
                    ClientboundMerchantOffersPacket.class, decode(protocol, withTrailingByte));
            assertEquivalent(expected, actual);
            assertEquals(0x7A, withTrailingByte.readUnsignedByte());
            assertFalse(withTrailingByte.isReadable());
        } finally {
            withTrailingByte.release();
        }
    }

    private static MerchantOffer offer(
            ItemCost firstCost,
            Optional<ItemCost> secondCost,
            ItemStack result,
            int uses,
            int maxUses,
            int xp,
            float priceMultiplier,
            int demand,
            int specialPriceDiff) {
        MerchantOffer offer = new MerchantOffer(
                firstCost, secondCost, result, uses, maxUses, xp, priceMultiplier, demand);
        offer.setSpecialPriceDiff(specialPriceDiff);
        return offer;
    }

    private static MerchantOffer offer(
            ItemCost firstCost,
            Optional<ItemCost> secondCost,
            ItemStack result,
            int uses,
            int maxUses,
            int xp,
            float priceMultiplier,
            int demand,
            int specialPriceDiff,
            boolean rewardExperience) {
        MerchantOffer offer = new MerchantOffer(
                firstCost,
                secondCost,
                result,
                uses,
                maxUses,
                xp,
                priceMultiplier,
                demand,
                rewardExperience,
                null);
        offer.setSpecialPriceDiff(specialPriceDiff);
        return offer;
    }

    private static ItemStack namedStack(net.minecraft.world.item.Item item, int count, String name) {
        ItemStack stack = stack(item, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static ItemCost cost(net.minecraft.world.item.Item item, int count) {
        bindComponents(item);
        return new ItemCost(item, count);
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        bindComponents(item);
        return new ItemStack(item.builtInRegistryHolder(), count);
    }

    private static void bindComponents(net.minecraft.world.item.Item item) {
        var holder = item.builtInRegistryHolder();
        if (!holder.areComponentsBound()) {
            holder.bindComponents(DataComponentMap.EMPTY);
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

    // Paper's outbound ItemStack codec resolves dummy enchantments through the active server.
    private static LayeredRegistryAccess<RegistryLayer> testRegistryAccess() {
        MappedRegistry<Enchantment> enchantments = new MappedRegistry<>(
                Registries.ENCHANTMENT, com.mojang.serialization.Lifecycle.stable());
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

    private static ClientboundMerchantOffersPacket packet(
            int containerId,
            MerchantOffers offers,
            int merchantLevel,
            int merchantXp,
            boolean showProgress,
            boolean canRestock) {
        return new ClientboundMerchantOffersPacket(
                containerId, offers, merchantLevel, merchantXp, showProgress, canRestock);
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundMerchantOffersPacket expected) {
        byte[] originalWire = encodePacketBytes(protocol, expected);
        ByteBuf encoded = Unpooled.wrappedBuffer(originalWire);
        try {
            ByteBuf header = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(header));

            ClientboundMerchantOffersPacket decoded = assertInstanceOf(
                    ClientboundMerchantOffersPacket.class, decode(protocol, encoded));
            assertEquivalent(expected, decoded);
            assertFalse(encoded.isReadable());
            assertArrayEquals(originalWire, encodePacketBytes(protocol, decoded));
        } finally {
            encoded.release();
        }
    }

    private static void assertEquivalent(
            ClientboundMerchantOffersPacket expected,
            ClientboundMerchantOffersPacket actual) {
        assertEquals(expected.getContainerId(), actual.getContainerId());
        assertEquals(expected.getVillagerLevel(), actual.getVillagerLevel());
        assertEquals(expected.getVillagerXp(), actual.getVillagerXp());
        assertEquals(expected.showProgress(), actual.showProgress());
        assertEquals(expected.canRestock(), actual.canRestock());
        assertEquals(expected.getOffers().size(), actual.getOffers().size());
        for (int index = 0; index < expected.getOffers().size(); index++) {
            assertOfferEquivalent(
                    expected.getOffers().get(index), actual.getOffers().get(index));
        }
    }

    private static void assertOfferEquivalent(MerchantOffer expected, MerchantOffer actual) {
        assertCostEquivalent(expected.getItemCostA(), actual.getItemCostA());
        assertEquals(expected.getItemCostB().isPresent(), actual.getItemCostB().isPresent());
        if (expected.getItemCostB().isPresent()) {
            assertCostEquivalent(
                    expected.getItemCostB().orElseThrow(), actual.getItemCostB().orElseThrow());
        }
        assertItemStackEquivalent(expected.getResult(), actual.getResult());
        assertEquals(expected.getUses(), actual.getUses());
        assertEquals(expected.getMaxUses(), actual.getMaxUses());
        assertEquals(expected.getXp(), actual.getXp());
        assertEquals(
                Float.floatToIntBits(expected.getPriceMultiplier()),
                Float.floatToIntBits(actual.getPriceMultiplier()));
        assertEquals(expected.getDemand(), actual.getDemand());
        assertEquals(expected.getSpecialPriceDiff(), actual.getSpecialPriceDiff());
        assertEquals(expected.shouldRewardExp(), actual.shouldRewardExp());
    }

    private static void assertCostEquivalent(ItemCost expected, ItemCost actual) {
        assertEquals(expected.item().value(), actual.item().value());
        assertEquals(expected.count(), actual.count());
        assertEquals(expected.components(), actual.components());
        assertItemStackEquivalent(expected.itemStack(), actual.itemStack());
    }

    private static void assertItemStackEquivalent(ItemStack expected, ItemStack actual) {
        assertEquals(expected.getItem(), actual.getItem());
        assertEquals(expected.getCount(), actual.getCount());
        assertEquals(expected.get(DataComponents.CUSTOM_NAME), actual.get(DataComponents.CUSTOM_NAME));
    }

    private static ProtocolInfo<?> protocol() {
        RegistryAccess registryAccess = RegistryAccess.fromRegistryOfRegistries(
                BuiltInRegistries.REGISTRY);
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static byte[] encodePacketBytes(ProtocolInfo<?> protocol, Packet<?> packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.getBytes(encoded.readerIndex(), bytes);
            return bytes;
        } finally {
            encoded.release();
        }
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

    private static PacketDescriptor descriptor() {
        return descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
            String typeName,
            int packetId,
            PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                true,
                false,
                "paper-26.2/" + typeName,
                scope);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes,
            AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local merchant offers must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local merchant offers must not decode");
            }
        };
    }
}
