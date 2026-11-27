package dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.definition.ui;

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
import dev.voldechse.replayframework.adapter.paper.v26_3.Paper26_3PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.packet.clientbound.Paper26_3ClientboundPacketCatalog;
import dev.voldechse.replayframework.adapter.paper.v26_3.playback.Paper26_3ReplayPacketRewriter;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.DialogListDialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.ServerLinksDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.SingleOptionInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class Id143ShowDialogPacketDefinitionTest {

    private static final int PACKET_ID = 143;
    private static final String TYPE_NAME = "play.clientbound.minecraft:show_dialog";
    private static final UUID OBSERVER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final ResourceKey<Dialog> REGISTERED_CHILD_KEY = ResourceKey.create(
            Registries.DIALOG,
            Identifier.fromNamespaceAndPath("replay_test", "registered_child_dialog"));
    private static final ResourceKey<Dialog> DIALOG_KEY = ResourceKey.create(
            Registries.DIALOG, Identifier.fromNamespaceAndPath("replay_test", "registered_dialog"));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesShowDialogAsViewerLocalState() {
        var definition = new Id143ShowDialogPacketDefinition();

        assertEquals(PACKET_ID, definition.packetId());
        assertEquals(0x8F, Id143ShowDialogPacketDefinition.PACKET_ID);
        assertEquals("minecraft:show_dialog", Id143ShowDialogPacketDefinition.IDENTIFIER);
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
    void registersExactlyOnceInUiDomainCatalogAndPlayRegistry() {
        var domainMatches = UiPacketDefinitions.definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();
        var catalogMatches = Paper26_3ClientboundPacketCatalog.catalog().definitions().stream()
                .filter(definition -> TYPE_NAME.equals(definition.typeName()))
                .toList();

        assertEquals(1, domainMatches.size());
        assertInstanceOf(Id143ShowDialogPacketDefinition.class, domainMatches.getFirst());
        assertEquals(1, catalogMatches.size());
        assertInstanceOf(Id143ShowDialogPacketDefinition.class, catalogMatches.getFirst());

        var descriptor = Paper26_3PacketRegistry.discover()
                .find(PacketPhase.PLAY, PacketDescriptor.Direction.CLIENTBOUND, PACKET_ID)
                .orElseThrow();
        assertEquals(TYPE_NAME, descriptor.typeName());
        assertEquals(PacketDisposition.STATEFUL, descriptor.disposition());
        assertTrue(descriptor.captureByDefault());
        assertTrue(descriptor.replayable());
        assertFalse(descriptor.checkpointRelevant());
        assertEquals(PacketDescriptor.PlaybackScope.VIEWER_LOCAL, descriptor.playbackScope());
        assertTrue(Paper26_3ClientboundPacketCatalog.explicitlyClassifies(TYPE_NAME));
    }

    @Test
    void nativeProtocolRoundTripsAllFourInlineDialogVariantsAndNestedContent() {
        ProtocolInfo<?> protocol = protocol();
        List<DialogCase> cases = dialogCases();

        assertEquals(4, cases.size());
        for (DialogCase dialogCase : cases) {
            assertNativeRoundTrip(protocol, dialogCase);
        }
    }

    @Test
    void nativeProtocolEncodesDirectDialogsWithTheZeroHolderMarker() {
        ByteBuf encoded = encodePacket(
                protocol(), new ClientboundShowDialogPacket(Holder.direct(dialogCases().getFirst().dialog())));
        try {
            assertEquals(PACKET_ID, VarInt.read(encoded));
            assertEquals(0, VarInt.read(encoded));
            assertTrue(encoded.isReadable(), "the direct marker is followed by the dialog definition");
        } finally {
            encoded.release();
        }
    }

    @Test
    void nativeProtocolSupportsRegisteredDialogHoldersUsingRegistryIdPlusOne() {
        RegistryFixture fixture = registryFixture();
        ProtocolInfo<?> protocol = protocol(fixture.access());
        Holder.Reference<Dialog> holder = fixture.registry().get(DIALOG_KEY).orElseThrow();
        ByteBuf encoded = encodePacket(protocol, new ClientboundShowDialogPacket(holder));
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(2, VarInt.read(wire), "registry ID one is encoded as holder marker two");
            assertFalse(wire.isReadable());

            var decoded = assertInstanceOf(ClientboundShowDialogPacket.class, decode(protocol, encoded));
            assertEquals(Holder.Kind.REFERENCE, decoded.dialog().kind());
            assertEquals(DIALOG_KEY, decoded.dialog().unwrapKey().orElseThrow());
            DialogListDialog listDialog =
                    assertInstanceOf(DialogListDialog.class, decoded.dialog().value());
            assertEquals(1, listDialog.dialogs().size());
            assertEquals(
                    REGISTERED_CHILD_KEY,
                    listDialog.dialogs().stream().findFirst().orElseThrow()
                            .unwrapKey().orElseThrow());
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

    @Test
    void nativeProtocolRejectsEveryTruncatedDirectDialogBodyPrefix() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundShowDialogPacket(Holder.direct(minimalNotice())));
        try {
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            byte[] body = bytes(wire);
            for (int length = 0; length < body.length; length++) {
                assertMalformed(protocol, java.util.Arrays.copyOf(body, length));
            }
        } finally {
            encoded.release();
        }

        RegistryFixture fixture = registryFixture();
        FriendlyByteBuf invalidHolderId = new FriendlyByteBuf(Unpooled.buffer());
        try {
            VarInt.write(invalidHolderId, PACKET_ID);
            VarInt.write(invalidHolderId, 1000);
            assertThrows(RuntimeException.class, () -> decode(protocol(fixture.access()), invalidHolderId));
        } finally {
            invalidHolderId.release();
        }
    }

    @Test
    void nativeProtocolLeavesFollowingPacketBytesUnreadAfterTheDialogDefinition() {
        ProtocolInfo<?> protocol = protocol();
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundShowDialogPacket(Holder.direct(dialogCases().getFirst().dialog())));
        try {
            encoded.writeByte(0x7A);
            assertInstanceOf(ClientboundShowDialogPacket.class, decode(protocol, encoded));
            assertEquals(0x7A, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void dropsValidAndMalformedDialogFramesBeforePlaybackCodecAccess() {
        var definition = new Id143ShowDialogPacketDefinition();
        AtomicInteger decodes = new AtomicInteger();
        AtomicInteger encodes = new AtomicInteger();
        PacketCodec<Object> codec = failingCodec(decodes, encodes);
        List<byte[]> payloads = List.of(
                nativePayload(new ClientboundShowDialogPacket(Holder.direct(dialogCases().getFirst().dialog()))),
                nativePayload(new ClientboundShowDialogPacket(Holder.direct(dialogCases().getLast().dialog()))),
                new byte[0],
                new byte[] {(byte) 0x80},
                new byte[] {0});

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
    void directRewriteDropsWithoutInspectingTheDialogPacket() {
        PacketRewriteResult result = new Id143ShowDialogPacketDefinition().rewrite(
                new Object(), descriptor(), rewriter());

        assertEquals(PacketRewriteResult.Decision.DROP_LOCAL, result.decision());
        assertTrue(result.packet().isEmpty());
    }

    @Test
    void showDialogDoesNotBelongToCheckpointState() {
        assertTrue(Paper26_3ClientboundPacketCatalog.catalog().require(TYPE_NAME).checkpointFamily().isEmpty());
    }

    @Test
    void rejectsDescriptorMismatchesBeforeApplyingViewerLocalPolicy() {
        var definition = new Id143ShowDialogPacketDefinition();
        PacketCodec<Object> codec = failingCodec(new AtomicInteger(), new AtomicInteger());
        List<PacketDescriptor> mismatches = List.of(
                descriptor("play.clientbound.minecraft:other", PACKET_ID,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID + 1,
                        PacketDescriptor.PlaybackScope.VIEWER_LOCAL),
                descriptor(TYPE_NAME, PACKET_ID, PacketDescriptor.PlaybackScope.SAFE));

        for (PacketDescriptor mismatch : mismatches) {
            assertThrows(IncompatibleAdapterException.class,
                    () -> definition.handlePlayback(frame(new byte[0]), mismatch, codec, rewriter()));
        }
    }

    private static List<DialogCase> dialogCases() {
        CommonDialogData common = commonData();
        ActionButton run = button("Continue", Optional.of(
                new StaticAction(new ClickEvent.RunCommand("/say replay dialog"))));
        ActionButton custom = button("Send", Optional.of(new CustomAll(
                Identifier.fromNamespaceAndPath("replay_test", "dialog_action"),
                Optional.of(customActionData()))));
        ActionButton exit = button("Back", Optional.empty());
        NoticeDialog notice = new NoticeDialog(common, run);

        return List.of(
                new DialogCase(notice, NoticeDialog.class),
                new DialogCase(new ConfirmationDialog(common, run, exit), ConfirmationDialog.class),
                new DialogCase(new MultiActionDialog(
                        common, List.of(run, custom), Optional.of(exit), 2), MultiActionDialog.class),
                new DialogCase(new ServerLinksDialog(common, Optional.of(exit), 3, 210),
                        ServerLinksDialog.class));
    }

    private static CommonDialogData commonData() {
        return new CommonDialogData(
                Component.translatable("dialog.replay.title", Component.literal("42")),
                Optional.of(Component.literal("Replay details")),
                true,
                true,
                DialogAction.WAIT_FOR_RESPONSE,
                List.of(
                        new PlainMessage(Component.literal("Welcome to the recorded dialog."), 240),
                        new ItemBody(
                                new ItemStackTemplate(Items.DIAMOND, 3),
                                Optional.of(new PlainMessage(Component.literal("Reward item"), 180)),
                                true,
                                false,
                                48,
                                40)),
                List.of(
                        new Input("enabled", new BooleanInput(
                                Component.literal("Enable"), true, "enabled", "disabled")),
                        new Input("amount", new NumberRangeInput(
                                220,
                                Component.literal("Amount"),
                                "options.generic_value",
                                new NumberRangeInput.RangeInfo(
                                        -5.0F, 10.0F, Optional.of(2.0F), Optional.of(0.5F)))),
                        new Input("mode", new SingleOptionInput(
                                220,
                                List.of(
                                        new SingleOptionInput.Entry(
                                                "easy", Optional.of(Component.literal("Easy")), true),
                                        new SingleOptionInput.Entry(
                                                "hard", Optional.of(Component.literal("Hard")), false)),
                                Component.literal("Mode"),
                                true)),
                        new Input("comment", new TextInput(
                                220,
                                Component.literal("Comment"),
                                true,
                                "recorded value",
                                64,
                                Optional.of(new TextInput.MultilineOptions(
                                        Optional.of(3), Optional.of(80)))))));
    }

    private static NoticeDialog minimalNotice() {
        CommonDialogData common = new CommonDialogData(
                Component.literal("Short dialog"),
                Optional.empty(),
                true,
                true,
                DialogAction.CLOSE,
                List.of(),
                List.of());
        return new NoticeDialog(common, button("Okay", Optional.empty()));
    }

    private static ActionButton button(String label, Optional<net.minecraft.server.dialog.action.Action> action) {
        return new ActionButton(
                new CommonButtonData(
                        Component.literal(label), Optional.of(Component.literal(label + " tooltip")), 175),
                action);
    }

    private static net.minecraft.nbt.CompoundTag customActionData() {
        net.minecraft.nbt.CompoundTag data = new net.minecraft.nbt.CompoundTag();
        data.putString("recording", "source-server");
        return data;
    }

    private static void assertNativeRoundTrip(ProtocolInfo<?> protocol, DialogCase dialogCase) {
        ByteBuf encoded = encodePacket(
                protocol, new ClientboundShowDialogPacket(Holder.direct(dialogCase.dialog())));
        try {
            byte[] originalWire = bytes(encoded);
            FriendlyByteBuf wire = new FriendlyByteBuf(encoded.duplicate());
            assertEquals(PACKET_ID, VarInt.read(wire));
            assertEquals(0, VarInt.read(wire));

            var decoded = assertInstanceOf(ClientboundShowDialogPacket.class, decode(protocol, encoded));
            assertEquals(Holder.Kind.DIRECT, decoded.dialog().kind());
            assertInstanceOf(dialogCase.expectedType(), decoded.dialog().value());
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

    private static RegistryFixture registryFixture() {
        MappedRegistry<Dialog> dialogs = new MappedRegistry<>(Registries.DIALOG, Lifecycle.stable(), false);
        Holder.Reference<Dialog> child = dialogs.register(
                REGISTERED_CHILD_KEY,
                new NoticeDialog(
                        new CommonDialogData(
                                Component.literal("Child dialog"),
                                Optional.empty(),
                                true,
                                true,
                                DialogAction.CLOSE,
                                List.of(),
                                List.of()),
                        button("Okay", Optional.empty())),
                RegistrationInfo.BUILT_IN);
        Dialog registered = new DialogListDialog(
                new CommonDialogData(
                        Component.literal("Registered dialog list"),
                        Optional.empty(),
                        true,
                        true,
                        DialogAction.CLOSE,
                        List.of(),
                        List.of()),
                HolderSet.direct(child),
                Optional.empty(),
                1,
                150);
        dialogs.register(DIALOG_KEY, registered, RegistrationInfo.BUILT_IN);
        dialogs.freeze();

        List<Registry<?>> registries = RegistryAccess
                .fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                .registries()
                .map(RegistryAccess.RegistryEntry::value)
                .collect(Collectors.toCollection(ArrayList::new));
        registries.add(dialogs);
        return new RegistryFixture(new RegistryAccess.ImmutableRegistryAccess(registries), dialogs);
    }

    private static byte[] nativePayload(Packet<?> packet) {
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
        return protocol(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    private static ProtocolInfo<?> protocol(RegistryAccess registryAccess) {
        return GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registryAccess));
    }

    private static Paper26_3ReplayPacketRewriter rewriter() {
        return new Paper26_3ReplayPacketRewriter(
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
                PacketDisposition.STATEFUL,
                true,
                true,
                false,
                "paper-26.3/" + typeName,
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
                throw new AssertionError("viewer-local packet must not be encoded");
            }

            @Override
            public Object decode(RawPacketFrame frame) {
                decodes.incrementAndGet();
                throw new AssertionError("viewer-local packet must not be decoded");
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

    private record DialogCase(Dialog dialog, Class<? extends Dialog> expectedType) {}

    private record RegistryFixture(RegistryAccess access, MappedRegistry<Dialog> registry) {}
}
