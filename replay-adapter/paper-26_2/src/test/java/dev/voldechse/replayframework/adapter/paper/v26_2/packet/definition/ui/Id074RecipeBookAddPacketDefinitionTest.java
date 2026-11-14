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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
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
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id074RecipeBookAddPacketDefinitionTest {

    private static final int PACKET_ID = 0x4A;
    private static final String TYPE_NAME = "play.clientbound.minecraft:recipe_book_add";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final int[] RECIPE_DISPLAY_IDS = {0, 1, 127, 128, Integer.MAX_VALUE};
    private static final int[] GROUP_IDS = {0, 1, 127, 128, Integer.MAX_VALUE};
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
    void classifiesAndRegistersRecipeBookContentsAsViewerLocalState() {
        var definition = new Id074RecipeBookAddPacketDefinition();

        assertEquals(74, definition.packetId());
        assertEquals(PACKET_ID, Id074RecipeBookAddPacketDefinition.PACKET_ID);
        assertEquals("minecraft:recipe_book_add", Id074RecipeBookAddPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertFalse(definition.classification().checkpointRelevant());
        assertEquals(
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL,
                definition.classification().playbackScope());

        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id074RecipeBookAddPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id074RecipeBookAddPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeCodecRoundTripsAllRecipeDisplaysCategoriesOptionsAndEntryFlags() {
        ProtocolInfo<?> protocol = protocol();
        List<ClientboundRecipeBookAddPacket.Entry> entries = entriesWithAllFlagsAndFields();

        assertEquals(5, recipeDisplays().size());
        assertEquals(11, slotDisplays().size());
        assertEquals(13, BuiltInRegistries.RECIPE_BOOK_CATEGORY.size());
        assertEquals(
                new HashSet<>(BuiltInRegistries.RECIPE_DISPLAY.stream().toList()),
                recipeDisplays().stream().map(RecipeDisplay::type).collect(Collectors.toSet()));
        assertEquals(
                new HashSet<>(BuiltInRegistries.SLOT_DISPLAY.stream().toList()),
                slotDisplays().stream().map(SlotDisplay::type).collect(Collectors.toSet()));
        assertEquals(256, entries.size());

        for (boolean replace : List.of(false, true)) {
            ClientboundRecipeBookAddPacket decoded = assertNativeRoundTrip(
                    protocol, new ClientboundRecipeBookAddPacket(entries, replace));
            assertEquals(replace, decoded.replace());
            for (int flags = 0; flags < 256; flags++) {
                ClientboundRecipeBookAddPacket.Entry entry = decoded.entries().get(flags);
                assertEquals(flags, Byte.toUnsignedInt(entry.flags()));
                assertEquals((flags & ClientboundRecipeBookAddPacket.Entry.FLAG_NOTIFICATION) != 0,
                        entry.notification());
                assertEquals((flags & ClientboundRecipeBookAddPacket.Entry.FLAG_HIGHLIGHT) != 0,
                        entry.highlight());
            }
        }

        assertEquals(List.of(), assertNativeRoundTrip(
                protocol, new ClientboundRecipeBookAddPacket(List.of(), false)).entries());
        assertEquals(List.of(), assertNativeRoundTrip(
                protocol, new ClientboundRecipeBookAddPacket(List.of(), true)).entries());
    }

    @Test
    void nativeCodecRejectsUnknownDisplayIdsAndEveryTruncatedRichBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();

        ByteBuf unknownRecipeDisplay = Unpooled.buffer();
        try {
            VarInt.write(unknownRecipeDisplay, PACKET_ID);
            VarInt.write(unknownRecipeDisplay, 1);
            VarInt.write(unknownRecipeDisplay, 0);
            VarInt.write(unknownRecipeDisplay, Integer.MAX_VALUE);
            assertThrows(RuntimeException.class, () -> decode(protocol, unknownRecipeDisplay));
        } finally {
            unknownRecipeDisplay.release();
        }

        ByteBuf unknownSlotDisplay = Unpooled.buffer();
        try {
            VarInt.write(unknownSlotDisplay, PACKET_ID);
            VarInt.write(unknownSlotDisplay, 1);
            VarInt.write(unknownSlotDisplay, 0);
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

        ClientboundRecipeBookAddPacket richPacket = new ClientboundRecipeBookAddPacket(
                entriesWithAllFlagsAndFields().subList(0, 30), true);
        ByteBuf encoded = encodePacket(protocol, richPacket);
        try {
            ByteBuf body = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(body));
            byte[] bodyBytes = bytes(body);
            for (int length = 0; length < bodyBytes.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(bodyBytes, length));
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundRecipeBookAddPacket expected = new ClientboundRecipeBookAddPacket(
                entriesWithAllFlagsAndFields().subList(0, 3), false);
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            encoded.writeByte(0x7A);
            ClientboundRecipeBookAddPacket decoded = assertInstanceOf(
                    ClientboundRecipeBookAddPacket.class, decode(protocol, encoded));
            assertEquals(expected.entries(), decoded.entries());
            assertFalse(decoded.replace());
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void dropsEveryRecipeDisplayFamilyAndMalformedPayloadBeforeCodecUse() {
        var definition = new Id074RecipeBookAddPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = new ArrayList<>();
        ProtocolInfo<?> protocol = protocol();

        for (RecipeDisplay display : recipeDisplays()) {
            RecipeDisplayEntry contents = new RecipeDisplayEntry(
                    new RecipeDisplayId(5),
                    display,
                    OptionalInt.empty(),
                    RecipeBookCategories.CRAFTING_MISC,
                    Optional.empty());
            payloads.add(nativePayload(
                    protocol,
                    new ClientboundRecipeBookAddPacket(
                            List.of(new ClientboundRecipeBookAddPacket.Entry(contents, (byte) 3)),
                            false)));
        }
        payloads.addAll(List.of(
                new byte[0],
                new byte[] {1},
                new byte[] {1, 0},
                new byte[] {1, 0, 0},
                new byte[] {(byte) 0xFF, (byte) 0x80, 0}));

        for (byte[] payload : payloads) {
            assertDropBeforeCodec(definition, codec, payload);
        }
        assertEquals(0, decodes.get());
        assertEquals(0, encodes.get());
    }

    @Test
    void directRewriteDropsRecordedRecipeBookChanges() {
        PacketRewriteResult result = new Id074RecipeBookAddPacketDefinition()
                .rewrite(new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id074RecipeBookAddPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.CONFIGURATION, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.SERVERBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, false,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID,
                        PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, true,
                        PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                    frame(new byte[0]), mismatch, codec, rewriter()));
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.rewrite(new Object(), mismatch, rewriter()));
        }
    }

    private static List<ClientboundRecipeBookAddPacket.Entry> entriesWithAllFlagsAndFields() {
        List<RecipeDisplay> displays = recipeDisplays();
        List<RecipeBookCategory> categories = BuiltInRegistries.RECIPE_BOOK_CATEGORY.stream().toList();
        List<Ingredient> requirements = List.of(
                Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT),
                Ingredient.of(Items.REDSTONE));
        int[] requirementModes = {0, 1, 2};
        List<ClientboundRecipeBookAddPacket.Entry> entries = new ArrayList<>(256);
        int index = 0;

        for (RecipeDisplay display : displays) {
            for (boolean hasGroup : List.of(false, true)) {
                for (int requirementMode : requirementModes) {
                    int displayId = index < RECIPE_DISPLAY_IDS.length
                            ? RECIPE_DISPLAY_IDS[index]
                            : index + 1;
                    OptionalInt group = hasGroup
                            ? OptionalInt.of(GROUP_IDS[index % GROUP_IDS.length])
                            : OptionalInt.empty();
                    Optional<List<Ingredient>> craftingRequirements = switch (requirementMode) {
                        case 0 -> Optional.empty();
                        case 1 -> Optional.of(List.of());
                        case 2 -> Optional.of(requirements);
                        default -> throw new IllegalStateException("unknown requirement mode");
                    };
                    entries.add(entry(
                            displayId,
                            display,
                            group,
                            categories.get(index % categories.size()),
                            craftingRequirements,
                            (byte) index));
                    index++;
                }
            }
        }

        for (int flags = index; flags < 256; flags++) {
            entries.add(entry(
                    flags + 1,
                    displays.get(flags % displays.size()),
                    flags % 2 == 0 ? OptionalInt.of(GROUP_IDS[flags % GROUP_IDS.length]) : OptionalInt.empty(),
                    categories.get(flags % categories.size()),
                    switch (flags % 3) {
                        case 0 -> Optional.empty();
                        case 1 -> Optional.of(List.of());
                        case 2 -> Optional.of(requirements);
                        default -> throw new IllegalStateException("unknown requirement mode");
                    },
                    (byte) flags));
        }

        return List.copyOf(entries);
    }

    private static ClientboundRecipeBookAddPacket.Entry entry(
            int displayId,
            RecipeDisplay display,
            OptionalInt group,
            RecipeBookCategory category,
            Optional<List<Ingredient>> craftingRequirements,
            byte flags) {
        return new ClientboundRecipeBookAddPacket.Entry(
                new RecipeDisplayEntry(
                        new RecipeDisplayId(displayId),
                        display,
                        group,
                        category,
                        craftingRequirements),
                flags);
    }

    private static List<RecipeDisplay> recipeDisplays() {
        List<SlotDisplay> slots = slotDisplays();
        SlotDisplay allVariants = new SlotDisplay.Composite(slots);
        SlotDisplay table = new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE);
        return List.of(
                new ShapedCraftingRecipeDisplay(
                        3, 3, slots.subList(0, 9), allVariants, table),
                new ShapelessCraftingRecipeDisplay(
                        slots, new SlotDisplay.ItemSlotDisplay(Items.CHEST), table),
                new FurnaceRecipeDisplay(
                        allVariants,
                        SlotDisplay.AnyFuel.INSTANCE,
                        new SlotDisplay.ItemSlotDisplay(Items.COOKED_BEEF),
                        new SlotDisplay.ItemSlotDisplay(Items.FURNACE),
                        200,
                        0.35F),
                new SmithingRecipeDisplay(
                        slots.get(8),
                        slots.get(4),
                        slots.get(7),
                        allVariants,
                        new SlotDisplay.ItemSlotDisplay(Items.SMITHING_TABLE)),
                new StonecutterRecipeDisplay(
                        allVariants,
                        new SlotDisplay.ItemSlotDisplay(Items.STONE_BRICKS),
                        new SlotDisplay.ItemSlotDisplay(Items.STONECUTTER)));
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
                new SlotDisplay.OnlyWithComponent(iron, DataComponents.DAMAGE),
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

    private static void assertDropBeforeCodec(
            Id074RecipeBookAddPacketDefinition definition,
            PacketCodec<Object> codec,
            byte[] payload) {
        PacketRewriteResult result = definition.handlePlayback(
                frame(payload), descriptor(), codec, rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
        assertEquals(
                "recorded connection state does not belong to the observer",
                result.reason());
    }

    private static ClientboundRecipeBookAddPacket assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundRecipeBookAddPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            byte[] originalWire = bytes(encoded);
            ByteBuf wire = encoded.duplicate();
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(expected.entries().size(), VarInt.read(wire));
            if (!expected.entries().isEmpty()) {
                RecipeDisplayEntry firstEntry = expected.entries().getFirst().contents();
                assertEquals(firstEntry.id().index(), VarInt.read(wire));
                assertEquals(
                        BuiltInRegistries.RECIPE_DISPLAY.getId(firstEntry.display().type()),
                        VarInt.read(wire));
                assertTrue(wire.isReadable(), "recipe display payload should follow its registry ID");
            }

            ClientboundRecipeBookAddPacket decoded = assertInstanceOf(
                    ClientboundRecipeBookAddPacket.class, decode(protocol, encoded));
            assertEquals(expected.entries(), decoded.entries());
            assertEquals(expected.replace(), decoded.replace());
            assertFalse(encoded.isReadable());

            ByteBuf reencoded = encodePacket(protocol, decoded);
            try {
                assertArrayEquals(originalWire, bytes(reencoded));
            } finally {
                reencoded.release();
            }
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static byte[] nativePayload(
            ProtocolInfo<?> protocol, ClientboundRecipeBookAddPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Packet<?> decode(ProtocolInfo<?> protocol, ByteBuf buffer) {
        return (Packet<?>) ((StreamCodec) protocol.codec()).decode(buffer);
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

        Map<ResourceKey<? extends Registry<?>>, Registry<?>> registries = new HashMap<>();
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
        return descriptor(TYPE_NAME, PACKET_ID, PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND, true,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
    }

    private static PacketDescriptor descriptor(
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
                throw new AssertionError("viewer-local recipe book state must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local recipe book state must not decode");
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
}
