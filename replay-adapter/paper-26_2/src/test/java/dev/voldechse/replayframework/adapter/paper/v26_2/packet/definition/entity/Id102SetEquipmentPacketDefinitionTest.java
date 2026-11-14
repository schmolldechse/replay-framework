package dev.voldechse.replayframework.adapter.paper.v26_2.packet.definition.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint.Paper26_2CheckpointEncoder;
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
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id102SetEquipmentPacketDefinitionTest {

    private static final int PACKET_ID = 102;
    private static final String TYPE_NAME = "play.clientbound.minecraft:set_equipment";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000121");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000122");
    private static final Map<Item, Object> originalItemComponents = new HashMap<>();
    private static Object originalServer;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        bindTestItemComponents();
        installTestGlobalConfiguration();
        installTestMinecraftServerForPaperItemSanitizer();
    }

    @AfterAll
    static void restoreMinecraftServerSingleton() {
        try {
            Field serverField = MinecraftServer.class.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            serverField.set(null, originalServer);
            restoreTestItemComponents();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not restore Minecraft server singleton", failure);
        }
    }

    @Test
    void classifiesEquipmentAsCheckpointRelevantIdentityBearingState() {
        var definition = new Id102SetEquipmentPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x66, Id102SetEquipmentPacketDefinition.PACKET_ID);
        assertEquals("minecraft:set_equipment", Id102SetEquipmentPacketDefinition.IDENTIFIER);
        assertEquals(TYPE_NAME, definition.typeName());
        assertEquals(PacketDisposition.STATEFUL, definition.classification().disposition());
        assertTrue(definition.classification().captureByDefault());
        assertTrue(definition.classification().replayable());
        assertTrue(definition.classification().checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                definition.classification().playbackScope());
        assertEquals(Paper26_2CheckpointEncoder.CheckpointPacketFamily.EQUIPMENT,
                Paper26_2CheckpointEncoder.familyFor(TYPE_NAME));

        var domainMatches = EntityPacketDefinitions.definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        var catalogMatches = Paper26_2PacketCatalog.catalog().definitions().stream()
                .filter(candidate -> TYPE_NAME.equals(candidate.typeName()))
                .toList();
        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id102SetEquipmentPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id102SetEquipmentPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_2PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertTrue(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES,
                descriptor.playbackScope());
    }

    @Test
    void nativeCodecUsesEntityVarIntAndAllEightTerminatedSlotIds() {
        ProtocolInfo<?> protocol = protocol();
        int entityId = 0x01020304;
        List<Pair<EquipmentSlot, ItemStack>> equipment = allSlots();
        ByteBuf encoded = encodePacket(protocol, packet(entityId, equipment));
        try {
            byte[] expectedWire = bytes(encoded);
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(
                    encoded.duplicate(), registryAccess());
            assertEquals(PACKET_ID, fields.readVarInt());
            assertEquals(entityId, fields.readVarInt());

            assertEquals(EquipmentSlot.VALUES.size(), equipment.size());
            for (int index = 0; index < equipment.size(); index++) {
                Pair<EquipmentSlot, ItemStack> expected = equipment.get(index);
                int slotHeader = fields.readUnsignedByte();
                assertEquals(expected.getFirst().ordinal(), slotHeader & 0x7F);
                assertEquals(index < equipment.size() - 1, (slotHeader & 0x80) != 0);
                ItemStack decodedStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(fields);
                assertTrue(ItemStack.matches(expected.getSecond(), decodedStack));
            }
            assertFalse(fields.isReadable());

            ClientboundSetEquipmentPacket decoded = assertInstanceOf(
                    ClientboundSetEquipmentPacket.class, decode(protocol, encoded));
            assertEquals(entityId, decoded.getEntity());
            assertEquals(equipment.size(), decoded.getSlots().size());
            for (int index = 0; index < equipment.size(); index++) {
                assertEquals(equipment.get(index).getFirst(), decoded.getSlots().get(index).getFirst());
                assertTrue(ItemStack.matches(
                        equipment.get(index).getSecond(), decoded.getSlots().get(index).getSecond()));
            }

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

    @Test
    void nativeCodecPreservesRepeatedSlotsEmptyClearsAndItemComponents() {
        ProtocolInfo<?> protocol = protocol();
        ItemStack namedSword = namedDamagedSword();
        List<Pair<EquipmentSlot, ItemStack>> equipment = List.of(
                Pair.of(EquipmentSlot.MAINHAND, namedSword),
                Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY),
                Pair.of(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE)));

        assertNativeRoundTrip(protocol, packet(55, equipment), equipment);
    }

    @Test
    void rewriteMapsOnlyTheEntityAndPreservesOrderedEntriesWithDetachedStacks() {
        var definition = new Id102SetEquipmentPacketDefinition();
        var context = rewriter();
        int recordedEntityId = 1701;
        ItemStack sword = namedDamagedSword();
        List<Pair<EquipmentSlot, ItemStack>> equipment = List.of(
                Pair.of(EquipmentSlot.MAINHAND, sword),
                Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY),
                Pair.of(EquipmentSlot.SADDLE, new ItemStack(Items.SADDLE)));
        ClientboundSetEquipmentPacket original = packet(recordedEntityId, equipment);

        var value = definition.read(original);
        assertNotSame(sword, value.entries().getFirst().itemStack());
        ItemStack exposedCopy = value.entries().getFirst().itemStack();
        exposedCopy.set(DataComponents.CUSTOM_NAME, Component.literal("Modified test copy"));
        assertTrue(ItemStack.matches(sword, value.entries().getFirst().itemStack()));

        var rewritten = definition.rewrite(value, context).value().orElseThrow();
        assertEquals(context.mapEntityId(recordedEntityId), rewritten.entityId());
        assertNotEquals(recordedEntityId, rewritten.entityId());
        assertEquals(
                equipment.stream().map(Pair::getFirst).toList(),
                rewritten.entries().stream().map(Id102SetEquipmentPacketDefinition.EquipmentEntry::slot).toList());
        for (int index = 0; index < equipment.size(); index++) {
            assertTrue(ItemStack.matches(
                    equipment.get(index).getSecond(), rewritten.entries().get(index).itemStack()));
        }

        ClientboundSetEquipmentPacket rebuilt = definition.rebuild(rewritten);
        assertEquals(rewritten.entityId(), rebuilt.getEntity());
        for (int index = 0; index < equipment.size(); index++) {
            assertEquals(equipment.get(index).getFirst(), rebuilt.getSlots().get(index).getFirst());
            assertTrue(ItemStack.matches(
                    equipment.get(index).getSecond(), rebuilt.getSlots().get(index).getSecond()));
        }
        assertNotSame(
                rewritten.entries().getFirst().itemStack(),
                rebuilt.getSlots().getFirst().getSecond());
        assertTrue(rebuilt.getSlots().get(1).getSecond().isEmpty());
        assertTrue(rebuilt.getSlots().getFirst().getSecond().has(DataComponents.CUSTOM_NAME));
    }

    @Test
    void playbackRewritesEntityIdentityAndEncodesTheNativeEquipmentList() {
        ProtocolInfo<?> protocol = protocol();
        var definition = new Id102SetEquipmentPacketDefinition();
        var context = rewriter();
        PacketCodec<Object> codec = codec(protocol);
        int recordedEntityId = 1702;
        List<Pair<EquipmentSlot, ItemStack>> equipment = allSlots();

        PacketRewriteResult result = definition.handlePlayback(
                frame(body(protocol, packet(recordedEntityId, equipment))),
                descriptor(),
                codec,
                context);
        ClientboundSetEquipmentPacket rebuilt = assertInstanceOf(
                ClientboundSetEquipmentPacket.class, result.packet().orElseThrow());

        assertEquals(PacketRewriteResult.Decision.SEND, result.decision());
        assertEquals(context.mapEntityId(recordedEntityId), rebuilt.getEntity());
        assertEquals(equipment.size(), rebuilt.getSlots().size());
        for (int index = 0; index < equipment.size(); index++) {
            assertEquals(equipment.get(index).getFirst(), rebuilt.getSlots().get(index).getFirst());
            assertTrue(ItemStack.matches(
                    equipment.get(index).getSecond(), rebuilt.getSlots().get(index).getSecond()));
        }

        PacketCodec.EncodedPacket encoded = definition.encode(rebuilt, descriptor(), codec);
        assertEquals(PACKET_ID, encoded.packetId());
        ClientboundSetEquipmentPacket decoded = assertInstanceOf(
                ClientboundSetEquipmentPacket.class,
                decodePayload(protocol, encoded.payload()));
        assertEquals(rebuilt.getEntity(), decoded.getEntity());
        assertEquals(rebuilt.getSlots().size(), decoded.getSlots().size());
        for (int index = 0; index < rebuilt.getSlots().size(); index++) {
            assertEquals(rebuilt.getSlots().get(index).getFirst(), decoded.getSlots().get(index).getFirst());
            assertTrue(ItemStack.matches(
                    rebuilt.getSlots().get(index).getSecond(), decoded.getSlots().get(index).getSecond()));
        }
    }

    @Test
    void nativeDecoderConsumesOneEquipmentPacketAndLeavesFollowingBytesUnread() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = encodePacket(protocol, packet(24, allSlots()));
        try {
            wire.writeByte(0x7A);
            ClientboundSetEquipmentPacket decoded = assertInstanceOf(
                    ClientboundSetEquipmentPacket.class, decode(protocol, wire));

            assertEquals(24, decoded.getEntity());
            assertEquals(EquipmentSlot.VALUES.size(), decoded.getSlots().size());
            assertEquals(0x7A, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void nativeDecoderRejectsEmptyEquipmentAndEveryTruncatedEntryPrefix() {
        ProtocolInfo<?> protocol = protocol();
        assertNativeDecodeFails(protocol, body(protocol, packet(23, List.of())));

        byte[] completeBody = body(protocol, packet(23, allSlots()));
        for (int length = 0; length < completeBody.length; length++) {
            assertNativeDecodeFails(protocol, java.util.Arrays.copyOf(completeBody, length));
        }

        ByteBuf unterminated = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(unterminated, registryAccess());
            VarInt.write(fields, PACKET_ID);
            VarInt.write(fields, 23);
            fields.writeByte(0x80);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(fields, ItemStack.EMPTY);
            assertThrows(RuntimeException.class, () -> decode(protocol, unterminated));
        } finally {
            unterminated.release();
        }
    }

    @Test
    void nativeDecoderRejectsEquipmentSlotIdsOutsideThePaperEnum() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf wire = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf fields = new RegistryFriendlyByteBuf(wire, registryAccess());
            VarInt.write(fields, PACKET_ID);
            VarInt.write(fields, 23);
            fields.writeByte(EquipmentSlot.VALUES.size());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(fields, ItemStack.EMPTY);

            assertThrows(RuntimeException.class, () -> decode(protocol, wire));
        } finally {
            wire.release();
        }
    }

    @Test
    void validatesDescriptorBeforeDecodingOrEncoding() {
        var definition = new Id102SetEquipmentPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes);
        ClientboundSetEquipmentPacket packet = packet(2, allSlots());

        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID + 1, true), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor("play.clientbound.minecraft:other", PACKET_ID, true),
                codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.handlePlayback(
                frame(new byte[0]), descriptor(TYPE_NAME, PACKET_ID, false), codec, rewriter()));
        assertThrows(IncompatibleAdapterException.class, () -> definition.encode(
                packet, descriptor(TYPE_NAME, PACKET_ID + 1, true), codec));
        assertEquals(0, decodes.get());
    }

    private static void assertNativeRoundTrip(
            ProtocolInfo<?> protocol,
            ClientboundSetEquipmentPacket packet,
            List<Pair<EquipmentSlot, ItemStack>> expectedEntries) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            byte[] expectedWire = bytes(encoded);
            ClientboundSetEquipmentPacket decoded = assertInstanceOf(
                    ClientboundSetEquipmentPacket.class, decode(protocol, encoded));
            assertEquals(packet.getEntity(), decoded.getEntity());
            assertEquals(expectedEntries.size(), decoded.getSlots().size());
            for (int index = 0; index < expectedEntries.size(); index++) {
                assertEquals(expectedEntries.get(index).getFirst(), decoded.getSlots().get(index).getFirst());
                assertTrue(ItemStack.matches(
                        expectedEntries.get(index).getSecond(), decoded.getSlots().get(index).getSecond()));
            }

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

    private static List<Pair<EquipmentSlot, ItemStack>> allSlots() {
        return EquipmentSlot.VALUES.stream()
                .map(slot -> Pair.of(
                        slot,
                        slot == EquipmentSlot.OFFHAND
                                ? ItemStack.EMPTY
                                : slot == EquipmentSlot.MAINHAND
                                        ? namedDamagedSword()
                                        : new ItemStack(Items.IRON_INGOT)))
                .toList();
    }

    private static ItemStack namedDamagedSword() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Recorded equipment"));
        sword.set(DataComponents.DAMAGE, 13);
        return sword;
    }

    private static ClientboundSetEquipmentPacket packet(
            int entityId, List<Pair<EquipmentSlot, ItemStack>> equipment) {
        return new ClientboundSetEquipmentPacket(entityId, equipment);
    }

    private static byte[] body(ProtocolInfo<?> protocol, ClientboundSetEquipmentPacket packet) {
        ByteBuf encoded = encodePacket(protocol, packet);
        try {
            FriendlyByteBuf fields = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, fields.readVarInt());
            return bytes(fields);
        } finally {
            encoded.release();
        }
    }

    private static Object decodePayload(ProtocolInfo<?> protocol, byte[] payload) {
        ByteBuf wire = Unpooled.buffer();
        try {
            VarInt.write(wire, PACKET_ID);
            wire.writeBytes(payload);
            return decode(protocol, wire);
        } finally {
            wire.release();
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
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                RegistryFriendlyByteBuf.decorator(registryAccess()));
    }

    private static RegistryAccess registryAccess() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
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

    private static void bindTestItemComponents() {
        try {
            for (Item item : List.of(Items.DIAMOND_SWORD, Items.IRON_INGOT, Items.SADDLE)) {
                Object holder = item.builtInRegistryHolder();
                Field componentsField = holder.getClass().getDeclaredField("components");
                componentsField.setAccessible(true);
                originalItemComponents.put(item, componentsField.get(holder));
                item.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not bind test item components", failure);
        }
    }

    private static void restoreTestItemComponents() throws ReflectiveOperationException {
        for (var entry : originalItemComponents.entrySet()) {
            Field componentsField = entry.getKey().builtInRegistryHolder().getClass()
                    .getDeclaredField("components");
            componentsField.setAccessible(true);
            componentsField.set(entry.getKey().builtInRegistryHolder(), entry.getValue());
        }
        originalItemComponents.clear();
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
        return descriptor(TYPE_NAME, PACKET_ID, true);
    }

    private static PacketDescriptor descriptor(String typeName, int packetId, boolean replayable) {
        return new PacketDescriptor(
                typeName,
                PacketPhase.PLAY,
                PacketDescriptor.Direction.CLIENTBOUND,
                packetId,
                PacketDisposition.STATEFUL,
                true,
                replayable,
                true,
                "paper-26.2/" + typeName,
                PacketDescriptor.PlaybackScope.REWRITE_IDENTITIES);
    }

    private static RawPacketFrame frame(byte[] payload) {
        return new RawPacketFrame(0L, 0L, 0, PacketPhase.PLAY, PACKET_ID, payload);
    }

    private static PacketCodec<Object> codec(ProtocolInfo<?> protocol) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                ClientboundSetEquipmentPacket nativePacket = assertInstanceOf(
                        ClientboundSetEquipmentPacket.class, packet);
                ByteBuf wire = encodePacket(protocol, nativePacket);
                try {
                    FriendlyByteBuf fields = new FriendlyByteBuf(wire.duplicate());
                    assertEquals(PACKET_ID, fields.readVarInt());
                    return new EncodedPacket(
                            PacketPhase.PLAY,
                            PacketDescriptor.Direction.CLIENTBOUND,
                            PACKET_ID,
                            bytes(fields));
                } finally {
                    wire.release();
                }
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                return decodePayload(protocol, frame.payload());
            }
        };
    }

    private static PacketCodec<Object> failingCodec(AtomicInteger decodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                throw new AssertionError("descriptor mismatch must not encode");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("descriptor mismatch must not decode");
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
