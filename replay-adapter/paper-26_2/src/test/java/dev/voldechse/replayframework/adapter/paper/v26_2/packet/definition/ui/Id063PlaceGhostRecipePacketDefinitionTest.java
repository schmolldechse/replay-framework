package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id063PlaceGhostRecipePacketDefinitionTest {

    private static final int PACKET_ID = 63;
    private static final String TYPE_NAME = "play.clientbound.minecraft:place_ghost_recipe";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int[] CONTAINER_IDS = {
        Integer.MIN_VALUE, -1, 0, 1, 127, 128, Integer.MAX_VALUE
    };
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
            var serverField = MinecraftServer.class.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            serverField.set(null, originalServer);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not restore Paper test server singleton", failure);
        }
    }

    @Test
    void classifiesGhostRecipePreviewAsViewerLocalEphemeralData() {
        var definition = new Id063PlaceGhostRecipePacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x3F, Id063PlaceGhostRecipePacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:place_ghost_recipe",
                Id063PlaceGhostRecipePacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());
    }

    @Test
    void registersExactlyOnceInUiDomainCatalogAndPaperRegistry() {
        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id063PlaceGhostRecipePacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id063PlaceGhostRecipePacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.EPHEMERAL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_2PacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void dropsEveryRecipeDisplayFamilyAndMalformedPayloadBeforeCodecDecodeOrEncode() {
        var definition = new Id063PlaceGhostRecipePacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = new ArrayList<>();
        for (RecipeDisplay recipeDisplay : recipeDisplays()) {
            payloads.add(nativePayload(packet(7, recipeDisplay)));
        }
        payloads.add(new byte[0]);
        payloads.add(new byte[] {1});
        payloads.add(new byte[16]);
        payloads.add(new byte[] {(byte) 0xFF, (byte) 0x80, 0});

        for (byte[] payload : payloads) {
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
    void directRewriteDropsWithoutInspectingTheDecodedPacket() {
        PacketRewriteResult result = new Id063PlaceGhostRecipePacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingLocalPolicy() {
        var definition = new Id063PlaceGhostRecipePacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(
                    IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(
                            frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    @Test
    void nativeProtocolRoundTripsEveryRegisteredRecipeAndSlotDisplayVariant() {
        ProtocolInfo<?> protocol = protocol();
        List<SlotDisplay> slotDisplays = slotDisplays();
        List<RecipeDisplay> recipeDisplays = recipeDisplays(slotDisplays);

        assertEquals(
                new HashSet<>(BuiltInRegistries.RECIPE_DISPLAY.stream().toList()),
                recipeDisplays.stream().map(RecipeDisplay::type).collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                new HashSet<>(BuiltInRegistries.SLOT_DISPLAY.stream().toList()),
                slotDisplays.stream().map(SlotDisplay::type).collect(java.util.stream.Collectors.toSet()));

        for (RecipeDisplay recipeDisplay : recipeDisplays) {
            for (int containerId : CONTAINER_IDS) {
                assertNativeRoundTrip(protocol, packet(containerId, recipeDisplay));
            }
        }
    }

    @Test
    void nativeProtocolRejectsUnknownRecipeAndSlotDisplayRegistryIds() {
        ProtocolInfo<?> protocol = protocol();

        ByteBuf unknownRecipeDisplay = Unpooled.buffer();
        try {
            VarInt.write(unknownRecipeDisplay, PACKET_ID);
            VarInt.write(unknownRecipeDisplay, 4);
            VarInt.write(unknownRecipeDisplay, Integer.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> decode(protocol, unknownRecipeDisplay));
        } finally {
            unknownRecipeDisplay.release();
        }

        ByteBuf unknownSlotDisplay = Unpooled.buffer();
        try {
            VarInt.write(unknownSlotDisplay, PACKET_ID);
            VarInt.write(unknownSlotDisplay, 4);
            VarInt.write(
                    unknownSlotDisplay,
                    BuiltInRegistries.RECIPE_DISPLAY.getId(ShapedCraftingRecipeDisplay.TYPE));
            VarInt.write(unknownSlotDisplay, 1);
            VarInt.write(unknownSlotDisplay, 1);
            VarInt.write(unknownSlotDisplay, 1);
            VarInt.write(unknownSlotDisplay, Integer.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> decode(protocol, unknownSlotDisplay));
        } finally {
            unknownSlotDisplay.release();
        }
    }

    @Test
    void nativeProtocolRejectsEveryTruncatedPacketBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        RecipeDisplay richDisplay = recipeDisplays(slotDisplays()).getFirst();
        ByteBuf encoded = encodePacket(protocol, packet(31, richDisplay));
        try {
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            for (int length = 0; length < body.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(body, length));
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        RecipeDisplay recipeDisplay = recipeDisplays(slotDisplays()).get(2);
        ByteBuf encoded = encodePacket(protocol, packet(13, recipeDisplay));
        try {
            encoded.writeByte(0x7A);
            ClientboundPlaceGhostRecipePacket decoded = assertInstanceOf(
                    ClientboundPlaceGhostRecipePacket.class, decode(protocol, encoded));
            assertEquals(13, decoded.containerId());
            assertEquals(recipeDisplay, decoded.recipeDisplay());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static List<SlotDisplay> slotDisplays() {
        SlotDisplay iron = new SlotDisplay.ItemSlotDisplay(Items.IRON_INGOT);
        SlotDisplay itemStack = new SlotDisplay.ItemStackSlotDisplay(
                new ItemStackTemplate(
                        Items.DIAMOND_SWORD.builtInRegistryHolder(),
                        1,
                        DataComponentPatch.builder().set(DataComponents.DAMAGE, 17).build()));
        SlotDisplay tag = new SlotDisplay.TagSlotDisplay(ItemTags.PLANKS);
        var trimPattern = Holder.direct(new TrimPattern(
                Identifier.withDefaultNamespace("sentry"), Component.literal("Sentry"), false));

        return List.of(
                SlotDisplay.Empty.INSTANCE,
                SlotDisplay.AnyFuel.INSTANCE,
                new SlotDisplay.WithAnyPotion(new SlotDisplay.ItemSlotDisplay(Items.POTION)),
                new SlotDisplay.OnlyWithComponent(iron,
                        net.minecraft.core.component.DataComponents.DAMAGE),
                iron,
                itemStack,
                tag,
                new SlotDisplay.DyedSlotDemo(
                        new SlotDisplay.ItemSlotDisplay(Items.DYE.gray()),
                        new SlotDisplay.ItemSlotDisplay(Items.LEATHER_CHESTPLATE)),
                new SlotDisplay.SmithingTrimDemoSlotDisplay(
                        new SlotDisplay.ItemSlotDisplay(Items.DIAMOND_CHESTPLATE),
                        new SlotDisplay.ItemSlotDisplay(Items.DIAMOND),
                        trimPattern),
                new SlotDisplay.WithRemainder(
                        new SlotDisplay.ItemSlotDisplay(Items.MILK_BUCKET),
                        new SlotDisplay.ItemSlotDisplay(Items.BUCKET)),
                new SlotDisplay.Composite(List.of(iron, itemStack, tag)));
    }

    private static List<RecipeDisplay> recipeDisplays() {
        return recipeDisplays(slotDisplays());
    }

    private static List<RecipeDisplay> recipeDisplays(List<SlotDisplay> slotDisplays) {
        SlotDisplay allVariants = new SlotDisplay.Composite(slotDisplays);
        SlotDisplay craftingTable = new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE);
        return List.of(
                new ShapedCraftingRecipeDisplay(
                        3,
                        3,
                        slotDisplays.subList(0, 9),
                        allVariants,
                        craftingTable),
                new ShapelessCraftingRecipeDisplay(
                        slotDisplays,
                        new SlotDisplay.ItemSlotDisplay(Items.CHEST),
                        craftingTable),
                new FurnaceRecipeDisplay(
                        allVariants,
                        SlotDisplay.AnyFuel.INSTANCE,
                        new SlotDisplay.ItemStackSlotDisplay(
                                new ItemStackTemplate(
                                        Items.COOKED_BEEF.builtInRegistryHolder(),
                                        1,
                                        DataComponentPatch.EMPTY)),
                        new SlotDisplay.ItemSlotDisplay(Items.FURNACE),
                        200,
                        0.35F),
                new SmithingRecipeDisplay(
                        slotDisplays.get(8),
                        slotDisplays.get(4),
                        slotDisplays.get(7),
                        allVariants,
                        new SlotDisplay.ItemSlotDisplay(Items.SMITHING_TABLE)),
                new StonecutterRecipeDisplay(
                        allVariants,
                        new SlotDisplay.ItemSlotDisplay(Items.STONE_BRICKS),
                        new SlotDisplay.ItemSlotDisplay(Items.STONECUTTER)));
    }

    private static ClientboundPlaceGhostRecipePacket packet(
            int containerId, RecipeDisplay recipeDisplay) {
        return new ClientboundPlaceGhostRecipePacket(containerId, recipeDisplay);
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundPlaceGhostRecipePacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(expected.containerId(), VarInt.read(wire));
            assertEquals(
                    BuiltInRegistries.RECIPE_DISPLAY.getId(expected.recipeDisplay().type()),
                    VarInt.read(wire));
            assertTrue(wire.isReadable(), "recipe display data should follow its registry ID");

            ClientboundPlaceGhostRecipePacket decoded = assertInstanceOf(
                    ClientboundPlaceGhostRecipePacket.class, decode(protocol, encoded));
            assertEquals(expected.containerId(), decoded.containerId());
            assertEquals(expected.recipeDisplay(), decoded.recipeDisplay());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(originalWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static byte[] nativePayload(ClientboundPlaceGhostRecipePacket packet) {
        ByteBuf encoded = encodePacket(protocol(), packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
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

    private static ProtocolInfo<?> protocol() {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));
    }

    private static void installTestGlobalConfiguration() {
        if (GlobalConfiguration.get() != null) {
            return;
        }
        try {
            GlobalConfiguration configuration = new GlobalConfiguration();
            configuration.misc = configuration.new Misc();
            configuration.packetLimiter = configuration.new PacketLimiter();
            configuration.packetLimiter.allPackets = new GlobalConfiguration.PacketLimiter.PacketLimit(
                    0.0D,
                    0.0D,
                    GlobalConfiguration.PacketLimiter.PacketLimit.ViolateAction.DROP);
            configuration.packetLimiter.overrides = Map.of();
            var setter = GlobalConfiguration.class.getDeclaredMethod("set", GlobalConfiguration.class);
            setter.setAccessible(true);
            setter.invoke(null, configuration);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not initialize Paper test configuration", failure);
        }
    }

    private static void installTestMinecraftServerForPaperItemSanitizer() {
        try {
            var serverField = MinecraftServer.class.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            originalServer = serverField.get(null);
            if (originalServer != null) {
                return;
            }

            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            var unsafeField = unsafeType.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Object testServer = unsafeType
                    .getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, DedicatedServer.class);

            var registriesField = MinecraftServer.class.getDeclaredField("registries");
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
            String typeName, int packetId, PacketDescriptor.PlaybackScope scope) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.EPHEMERAL,
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
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local recipe preview must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local recipe preview must not decode");
            }
        };
    }

    private static void assertMalformed(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf input = Unpooled.buffer();
        try {
            VarInt.write(input, PACKET_ID);
            input.writeBytes(payload);
            assertThrows(RuntimeException.class, () -> decode(protocol, input));
        } finally {
            input.release();
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
}
