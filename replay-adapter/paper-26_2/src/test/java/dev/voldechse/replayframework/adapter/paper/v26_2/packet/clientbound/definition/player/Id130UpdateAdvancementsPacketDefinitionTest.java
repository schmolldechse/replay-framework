package dev.voldechse.replayframework.adapter.paper.v26_2.packet.clientbound.definition.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.ImpossibleTrigger;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id130UpdateAdvancementsPacketDefinitionTest {

    private static final int PACKET_ID = 130;
    private static final String TYPE_NAME =
            "play.clientbound.minecraft:update_advancements";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Identifier FIRST_ADVANCEMENT =
            Identifier.parse("replay:test_first");
    private static final Identifier SECOND_ADVANCEMENT =
            Identifier.parse("replay:test_second");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void classifiesAdvancementUpdatesAsRecordedPlayerState() {
        var definition = new Id130UpdateAdvancementsPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x82, Id130UpdateAdvancementsPacketDefinition.PACKET_ID);
        assertEquals(
                "minecraft:update_advancements",
                Id130UpdateAdvancementsPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInPlayerDomainCatalogAndPaperRegistry() {
        var domainMatches = PlayerPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_2ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id130UpdateAdvancementsPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id130UpdateAdvancementsPacketDefinition.class, catalogMatches.getFirst());

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
    void nativeProtocolPreservesEveryPacketFieldAndBothFlagValues() {
        ProtocolInfo<?> protocol = protocol();

        for (ClientboundUpdateAdvancementsPacket source : List.of(
                packet(false, true),
                packet(true, false),
                emptyPacket(false, false),
                emptyPacket(true, true))) {
            ByteBuf encoded = encodePacket(protocol, source);
            try {
                ByteBuf idReader = encoded.duplicate();
                assertEquals(PACKET_ID, VarInt.read(idReader));

                ClientboundUpdateAdvancementsPacket decoded =
                        assertInstanceOf(ClientboundUpdateAdvancementsPacket.class,
                                decode(protocol, encoded.duplicate()));
                assertPacketFieldsEqual(source, decoded);
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void progressMapPreservesIncompleteAndCompletedCriterionTimestamps() {
        ProtocolInfo<?> protocol = protocol();
        ClientboundUpdateAdvancementsPacket source = packet(false, true);
        ByteBuf encoded = encodePacket(protocol, source);
        try {
            ClientboundUpdateAdvancementsPacket decoded =
                    assertInstanceOf(ClientboundUpdateAdvancementsPacket.class,
                            decode(protocol, encoded.duplicate()));

            for (Identifier id : List.of(FIRST_ADVANCEMENT, SECOND_ADVANCEMENT)) {
                AdvancementProgress expected = source.getProgress().get(id);
                AdvancementProgress actual = decoded.getProgress().get(id);
                assertNotNull(actual);
                for (String criterion : List.of("first", "second", "complete")) {
                    CriterionProgress expectedCriterion = expected.getCriterion(criterion);
                    if (expectedCriterion == null) {
                        assertNull(actual.getCriterion(criterion));
                    } else {
                        CriterionProgress actualCriterion = actual.getCriterion(criterion);
                        assertNotNull(actualCriterion);
                        assertEquals(expectedCriterion.isDone(), actualCriterion.isDone());
                        assertObtainedTimeEqual(expectedCriterion, actualCriterion);
                    }
                }
            }
        } finally {
            encoded.release();
        }
    }

    @Test
    void viewerLocalPlaybackDropsValidAndMalformedFramesBeforeCodecAccess() {
        var definition = new Id130UpdateAdvancementsPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);

        for (byte[] payload : List.of(
                new byte[0],
                new byte[] {(byte) 0xFF, 0x01},
                packetBytes(packet(false, true)))) {
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
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id130UpdateAdvancementsPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());

        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(
                        frame(new byte[0]),
                        descriptor("play.clientbound.minecraft:other", PACKET_ID,
                                PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                        codec,
                        rewriter()));
        assertThrows(IncompatibleAdapterException.class,
                () -> definition.handlePlayback(
                        frame(new byte[0]),
                        descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE),
                        codec,
                        rewriter()));
    }

    @Test
    void rejectsEveryTruncatedPrefixOfTheNativePacket() {
        ProtocolInfo<?> protocol = protocol();
        byte[] complete = packetBytes(packet(true, false));

        for (int length = 0; length < complete.length; length++) {
            ByteBuf truncated = Unpooled.wrappedBuffer(Arrays.copyOf(complete, length));
            try {
                assertThrows(RuntimeException.class, () -> decode(protocol, truncated),
                        "truncated length " + length);
            } finally {
                truncated.release();
            }
        }
    }

    @Test
    void nativePacketDefensivelyCopiesItsOuterCollections() {
        List<AdvancementHolder> added = new ArrayList<>(List.of(advancement(
                "replay:mutable", false, "first")));
        Set<Identifier> removed = new LinkedHashSet<>(Set.of(Identifier.parse("replay:removed")));
        Map<Identifier, AdvancementProgress> progress = new HashMap<>();
        progress.put(FIRST_ADVANCEMENT, progress(List.of("first", "second"), "first"));

        var packet = new ClientboundUpdateAdvancementsPacket(
                false, added, removed, progress, true);
        added.clear();
        removed.clear();
        progress.clear();

        assertEquals(1, packet.getAdded().size());
        assertEquals(Set.of(Identifier.parse("replay:removed")), packet.getRemoved());
        assertTrue(packet.getProgress().containsKey(FIRST_ADVANCEMENT));
        assertThrows(UnsupportedOperationException.class,
                () -> packet.getAdded().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> packet.getRemoved().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> packet.getProgress().clear());
    }

    private static ClientboundUpdateAdvancementsPacket packet(
            boolean reset, boolean showAdvancements) {
        AdvancementHolder first = advancement(
                "replay:first", true, "first", "second");
        AdvancementHolder second = advancement(
                "replay:second", false, "complete");
        Map<Identifier, AdvancementProgress> progress = Map.of(
                FIRST_ADVANCEMENT, progress(List.of("first", "second"), "first"),
                SECOND_ADVANCEMENT, progress(List.of("complete"), "complete"));

        return new ClientboundUpdateAdvancementsPacket(
                reset,
                List.of(first, second),
                new LinkedHashSet<>(List.of(
                        Identifier.parse("replay:removed_one"),
                        Identifier.parse("replay:removed_two"))),
                progress,
                showAdvancements);
    }

    private static ClientboundUpdateAdvancementsPacket emptyPacket(
            boolean reset, boolean showAdvancements) {
        return new ClientboundUpdateAdvancementsPacket(
                reset, List.of(), Set.of(), Map.of(), showAdvancements);
    }

    private static AdvancementHolder advancement(
            String id, boolean withDisplay, String... criteria) {
        var builder = net.minecraft.advancements.Advancement.Builder.advancement();
        for (String criterion : criteria) {
            builder.addCriterion(
                    criterion,
                    CriteriaTriggers.IMPOSSIBLE.createCriterion(
                            new ImpossibleTrigger.TriggerInstance()));
        }
        if (criteria.length > 1) {
            builder.requirements(AdvancementRequirements.anyOf(List.of(criteria)));
        }
        if (withDisplay) {
            builder.display(
                    Items.DIAMOND,
                    Component.literal("Replay advancement"),
                    Component.literal("Complete the packet test"),
                    null,
                    AdvancementType.TASK,
                    true,
                    false,
                    true);
        }
        AdvancementHolder holder = builder.build(Identifier.parse(id));
        holder.value().display().ifPresent(display -> display.setLocation(-2.5F, 8.25F));
        return holder;
    }

    private static AdvancementProgress progress(List<String> criteria, String... completed) {
        AdvancementProgress progress = new AdvancementProgress();
        progress.update(AdvancementRequirements.allOf(criteria));
        for (String criterion : completed) {
            assertTrue(progress.grantProgress(criterion));
        }
        return progress;
    }

    private static void assertPacketFieldsEqual(
            ClientboundUpdateAdvancementsPacket expected,
            ClientboundUpdateAdvancementsPacket actual) {
        assertEquals(expected.shouldReset(), actual.shouldReset());
        assertEquals(expected.shouldShowAdvancements(), actual.shouldShowAdvancements());
        assertEquals(expected.getRemoved(), actual.getRemoved());
        assertEquals(expected.getAdded().stream().map(AdvancementHolder::id).toList(),
                actual.getAdded().stream().map(AdvancementHolder::id).toList());
        assertEquals(expected.getAdded().size(), actual.getAdded().size());
        for (int index = 0; index < expected.getAdded().size(); index++) {
            var expectedAdvancement = expected.getAdded().get(index).value();
            var actualAdvancement = actual.getAdded().get(index).value();
            assertEquals(expectedAdvancement.parent(), actualAdvancement.parent());
            assertEquals(expectedAdvancement.requirements(), actualAdvancement.requirements());
            assertEquals(expectedAdvancement.sendsTelemetryEvent(),
                    actualAdvancement.sendsTelemetryEvent());
            assertEquals(expectedAdvancement.display().isPresent(),
                    actualAdvancement.display().isPresent());
            expectedAdvancement.display().ifPresent(expectedDisplay -> {
                var actualDisplay = actualAdvancement.display().orElseThrow();
                assertEquals(expectedDisplay.getTitle(), actualDisplay.getTitle());
                assertEquals(expectedDisplay.getDescription(), actualDisplay.getDescription());
                assertEquals(expectedDisplay.getType(), actualDisplay.getType());
                assertEquals(expectedDisplay.getIcon(), actualDisplay.getIcon());
                assertEquals(expectedDisplay.shouldShowToast(), actualDisplay.shouldShowToast());
                assertEquals(expectedDisplay.isHidden(), actualDisplay.isHidden());
                assertEquals(expectedDisplay.getX(), actualDisplay.getX());
                assertEquals(expectedDisplay.getY(), actualDisplay.getY());
            });
        }
        assertEquals(expected.getProgress().keySet(), actual.getProgress().keySet());
        for (Identifier id : expected.getProgress().keySet()) {
            AdvancementProgress expectedProgress = expected.getProgress().get(id);
            AdvancementProgress actualProgress = actual.getProgress().get(id);
            assertNotNull(actualProgress);
            for (String criterion : List.of("first", "second", "complete")) {
                CriterionProgress expectedCriterion = expectedProgress.getCriterion(criterion);
                if (expectedCriterion != null) {
                    CriterionProgress actualCriterion = actualProgress.getCriterion(criterion);
                    assertNotNull(actualCriterion);
                    assertEquals(expectedCriterion.isDone(), actualCriterion.isDone());
                    assertObtainedTimeEqual(expectedCriterion, actualCriterion);
                } else {
                    assertNull(actualProgress.getCriterion(criterion));
                }
            }
        }
    }

    private static void assertObtainedTimeEqual(
            CriterionProgress expected, CriterionProgress actual) {
        if (expected.getObtained() == null) {
            assertNull(actual.getObtained());
        } else {
            assertEquals(
                    expected.getObtained().truncatedTo(ChronoUnit.MILLIS),
                    actual.getObtained());
        }
    }

    private static byte[] packetBytes(ClientboundUpdateAdvancementsPacket packet) {
        ByteBuf encoded = encodePacket(protocol(), packet);
        try {
            return bytes(encoded);
        } finally {
            encoded.release();
        }
    }

    private static ByteBuf encodePacket(
            ProtocolInfo<?> protocol, ClientboundUpdateAdvancementsPacket packet) {
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

    private static Paper26_2ReplayPacketRewriter rewriter() {
        return new Paper26_2ReplayPacketRewriter(
                new PlaybackIdentityContext(OBSERVER_ID, SESSION_ID),
                Set.of(OBSERVER_ID),
                Set.of());
    }

    private static PacketCodec<Object> failingCodec(
            AtomicInteger decodes, AtomicInteger encodes) {
        return new PacketCodec<>() {
            @Override
            public EncodedPacket encode(Object packet) {
                encodes.incrementAndGet();
                throw new AssertionError("viewer-local packet must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local packet must not be decoded");
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
