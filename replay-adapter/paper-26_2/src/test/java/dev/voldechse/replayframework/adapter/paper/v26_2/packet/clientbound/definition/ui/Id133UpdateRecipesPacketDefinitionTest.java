package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.ui;

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
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketRewriteResult;
import dev.voldechse.replayframework.adapter.paper.v26_2.Paper26_2PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.Paper26_2ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_2.playback.Paper26_2ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import net.minecraft.core.component.DataComponentMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id133UpdateRecipesPacketDefinitionTest {

    private static final int PACKET_ID = 133;
    private static final String TYPE_NAME = "play.clientbound.minecraft:update_recipes";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000133");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000134");
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
            throw new AssertionError("could not restore Minecraft server singleton", failure);
        }
    }

    @Test
    void classifiesAndRegistersRecipeSynchronizationAsViewerLocalState() {
        var definition = new Id133UpdateRecipesPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x85, Id133UpdateRecipesPacketDefinition.PACKET_ID);
        assertEquals("minecraft:update_recipes", Id133UpdateRecipesPacketDefinition.IDENTIFIER);
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
        var catalogMatches = Paper26_2ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id133UpdateRecipesPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id133UpdateRecipesPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_2ClientboundPacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeCodecRoundTripsEveryPropertyKeyAndSlotDisplayFamily() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundUpdateRecipesPacket empty = assertNativeRoundTrip(
                protocol,
                new ClientboundUpdateRecipesPacket(
                        Map.of(), SelectableRecipe.SingleInputSet.empty()));
        assertTrue(empty.itemSets().isEmpty());
        assertTrue(empty.stonecutterRecipes().isEmpty());

        Map<ResourceKey<RecipePropertySet>, List<Item>> expectedSets = expectedItemSets();
        Map<ResourceKey<RecipePropertySet>, RecipePropertySet> propertySets = new LinkedHashMap<>();
        expectedSets.forEach((key, items) -> propertySets.put(
                key,
                items.isEmpty()
                        ? RecipePropertySet.EMPTY
                        : RecipePropertySet.create(items.stream()
                                .map(item -> Ingredient.of(item))
                                .toList())));

        List<SlotDisplay> displays = slotDisplays();
        assertEquals(
                BuiltInRegistries.SLOT_DISPLAY.size(),
                displays.stream().map(SlotDisplay::type).collect(Collectors.toSet()).size());
        assertEquals(
                Set.copyOf(BuiltInRegistries.SLOT_DISPLAY.stream().toList()),
                displays.stream().map(SlotDisplay::type).collect(Collectors.toSet()));

        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries = new ArrayList<>();
        RecipeHolder<StonecutterRecipe> serverRecipe = sampleStonecutterRecipe();
        for (int index = 0; index < displays.size(); index++) {
            Ingredient input = index % 2 == 0
                    ? Ingredient.of(Items.STONE, Items.COBBLESTONE)
                    : Ingredient.of(Items.BRICKS);
            Optional<RecipeHolder<StonecutterRecipe>> recipe = index % 2 == 0
                    ? Optional.of(serverRecipe)
                    : Optional.empty();
            entries.add(new SelectableRecipe.SingleInputEntry<>(
                    input,
                    new SelectableRecipe<>(displays.get(index), recipe)));
        }

        ClientboundUpdateRecipesPacket expected = new ClientboundUpdateRecipesPacket(
                propertySets,
                new SelectableRecipe.SingleInputSet<>(entries));
        ClientboundUpdateRecipesPacket decoded = assertNativeRoundTrip(protocol, expected);

        assertEquals(expectedSets.keySet(), decoded.itemSets().keySet());
        for (var expectedSet : expectedSets.entrySet()) {
            RecipePropertySet decodedSet = decoded.itemSets().get(expectedSet.getKey());
            assertTrue(decodedSet != null);
            assertArrayEquals(
                    encodePropertySet(propertySets.get(expectedSet.getKey())),
                    encodePropertySet(decodedSet),
                    expectedSet.getKey().toString());
        }

        assertEquals(entries.size(), decoded.stonecutterRecipes().entries().size());
        for (int index = 0; index < entries.size(); index++) {
            var expectedEntry = entries.get(index);
            var decodedEntry = decoded.stonecutterRecipes().entries().get(index);
            assertEquals(expectedEntry.input(), decodedEntry.input());
            assertEquals(
                    expectedEntry.recipe().optionDisplay(),
                    decodedEntry.recipe().optionDisplay());
            // The native no-recipe codec sends the stonecutter option display, not the recipe body.
            assertTrue(decodedEntry.recipe().recipe().isEmpty());
        }
    }

    @Test
    void nativePacketEncodesTagBackedStonecutterInputsAsRegistryTagKeys() {
        ProtocolInfo<?> protocol = protocol();
        Ingredient tagInput = Ingredient.of(
                HolderSet.emptyNamed(BuiltInRegistries.ITEM, ItemTags.PLANKS));
        var entry = new SelectableRecipe.SingleInputEntry<>(
                tagInput,
                new SelectableRecipe<StonecutterRecipe>(
                        new SlotDisplay.ItemSlotDisplay(Items.OAK_PLANKS), Optional.empty()));
        ByteBuf encoded = encodePacket(
                protocol,
                new ClientboundUpdateRecipesPacket(
                        Map.of(), new SelectableRecipe.SingleInputSet<>(List.of(entry))));
        try {
            RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(
                    encoded,
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(0, VarInt.read(wire)); // no property sets
            assertEquals(1, VarInt.read(wire)); // one stonecutter choice
            assertEquals(0, VarInt.read(wire)); // named HolderSet marker
            assertEquals(ItemTags.PLANKS.location(), Identifier.STREAM_CODEC.decode(wire));
            assertEquals(
                    new SlotDisplay.ItemSlotDisplay(Items.OAK_PLANKS),
                    SlotDisplay.STREAM_CODEC.decode(wire));
            assertFalse(wire.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeCodecRejectsTruncatedBodiesAndLeavesFollowingPacketBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(protocol, populatedPacket());
        try {
            byte[] wire = bytes(encoded);
            for (int length = 0; length < wire.length; length++) {
                ByteBuf prefix = Unpooled.wrappedBuffer(Arrays.copyOf(wire, length));
                try {
                    assertThrows(RuntimeException.class, () -> decode(protocol, prefix));
                } finally {
                    prefix.release();
                }
            }
        } finally {
            encoded.release();
        }

        ByteBuf withFollowingByte = encodePacket(protocol, populatedPacket());
        try {
            withFollowingByte.writeByte(0x7A);
            ClientboundUpdateRecipesPacket decoded = assertInstanceOf(
                    ClientboundUpdateRecipesPacket.class, decode(protocol, withFollowingByte));
            assertFalse(decoded.itemSets().isEmpty());
            assertEquals(0x7A, withFollowingByte.readUnsignedByte());
            assertFalse(withFollowingByte.isReadable());
        } finally {
            withFollowingByte.release();
        }
    }

    @Test
    void playbackDropsValidMalformedAndOpaqueBodiesBeforeCodecUse() {
        var definition = new Id133UpdateRecipesPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        byte[] validBody = packetBody(protocol(), populatedPacket());

        for (byte[] payload : List.of(
                validBody,
                new byte[0],
                new byte[] {0},
                new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80},
                new byte[] {0x7F, (byte) 0xFF, 0x00, 0x42})) {
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

        PacketRewriteResult directRewrite = definition.rewrite(
                new Object(), descriptor(), rewriter());
        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, directRewrite.decision());
        assertTrue(directRewrite.packet().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchBeforeDroppingThePacket() {
        var definition = new Id133UpdateRecipesPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, new AtomicInteger());

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(PACKET_ID + 1), codec, rewriter()));
        assertEquals(0, decodes.get());
    }

    private static ClientboundUpdateRecipesPacket populatedPacket() {
        Map<ResourceKey<RecipePropertySet>, RecipePropertySet> propertySets = new LinkedHashMap<>();
        expectedItemSets().forEach((key, items) -> propertySets.put(
                key,
                items.isEmpty()
                        ? RecipePropertySet.EMPTY
                        : RecipePropertySet.create(items.stream()
                                .map(item -> Ingredient.of(item))
                                .toList())));
        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries = new ArrayList<>();
        List<SlotDisplay> displays = slotDisplays();
        RecipeHolder<StonecutterRecipe> recipe = sampleStonecutterRecipe();
        for (int index = 0; index < displays.size(); index++) {
            entries.add(new SelectableRecipe.SingleInputEntry<>(
                    Ingredient.of(Items.STONE, Items.COBBLESTONE),
                    new SelectableRecipe<>(
                            displays.get(index),
                            index % 2 == 0 ? Optional.of(recipe) : Optional.empty())));
        }
        return new ClientboundUpdateRecipesPacket(
                propertySets, new SelectableRecipe.SingleInputSet<>(entries));
    }

    private static Map<ResourceKey<RecipePropertySet>, List<Item>> expectedItemSets() {
        Map<ResourceKey<RecipePropertySet>, List<Item>> expected = new LinkedHashMap<>();
        expected.put(RecipePropertySet.SMITHING_BASE, List.of(Items.DIAMOND, Items.GOLD_INGOT));
        expected.put(RecipePropertySet.SMITHING_TEMPLATE, List.of(Items.PAPER));
        expected.put(RecipePropertySet.SMITHING_ADDITION, List.of(Items.NETHERITE_INGOT));
        expected.put(RecipePropertySet.FURNACE_INPUT, List.of(Items.COAL));
        expected.put(RecipePropertySet.BLAST_FURNACE_INPUT, List.of(Items.IRON_ORE, Items.COPPER_ORE));
        expected.put(RecipePropertySet.SMOKER_INPUT, List.of(Items.BEEF));
        expected.put(RecipePropertySet.CAMPFIRE_INPUT, List.of(Items.POTATO));
        expected.put(
                ResourceKey.create(
                        RecipePropertySet.TYPE_KEY,
                        Identifier.parse("replay:test_property_set")),
                List.of());
        return expected;
    }

    private static RecipeHolder<StonecutterRecipe> sampleStonecutterRecipe() {
        ResourceKey<Recipe<?>> recipeKey = ResourceKey.create(
                Registries.RECIPE,
                Identifier.parse("replay:test_stonecutter"));
        StonecutterRecipe recipe = new StonecutterRecipe(
                new Recipe.CommonInfo(true),
                Ingredient.of(Items.STONE),
                new ItemStackTemplate(
                        Items.STONE_BRICKS.builtInRegistryHolder(),
                        1,
                        DataComponentPatch.builder()
                                .set(DataComponents.DAMAGE, 17)
                                .build()));
        return new RecipeHolder<>(recipeKey, recipe);
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

    private static ClientboundUpdateRecipesPacket assertNativeRoundTrip(
            ProtocolInfo<?> protocol, ClientboundUpdateRecipesPacket expected) {
        ByteBuf encoded = encodePacket(protocol, expected);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded.duplicate()));
            ClientboundUpdateRecipesPacket decoded = assertInstanceOf(
                    ClientboundUpdateRecipesPacket.class, decode(protocol, encoded));
            assertFalse(encoded.isReadable());
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static byte[] packetBody(
            ProtocolInfo<?> protocol, ClientboundUpdateRecipesPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static byte[] encodePropertySet(RecipePropertySet propertySet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf registryBuffer = new RegistryFriendlyByteBuf(
                    buffer, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            RecipePropertySet.STREAM_CODEC.encode(registryBuffer, propertySet);
            return bytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static ByteBuf encodePacket(
            ProtocolInfo<?> protocol, ClientboundUpdateRecipesPacket packet) {
        ByteBuf encoded = Unpooled.buffer();
        try {
            encode(protocol, encoded, packet);
            return encoded;
        } catch (RuntimeException | Error failure) {
            encoded.release();
            throw failure;
        }
    }

    private static byte[] bytes(ByteBuf buffer) {
        byte[] result = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), result);
        return result;
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
        return descriptor(PACKET_ID);
    }

    private static PacketDescriptor descriptor(int packetId) {
        return new PacketDescriptor(
                TYPE_NAME,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                true,
                false,
                "paper-26.2/" + TYPE_NAME,
                PacketDescriptor.PlaybackScope.VIEWER_LOCAL);
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
                throw new AssertionError("viewer-local recipe state must not encode");
            }

            @Override
            public Object decode(RawPacketFrame ignored) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local recipe state must not decode");
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
