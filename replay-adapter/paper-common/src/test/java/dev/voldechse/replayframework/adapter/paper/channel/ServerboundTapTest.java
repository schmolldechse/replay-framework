package dev.voldechse.replayframework.adapter.paper.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffects;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The serverbound tap in a pipeline shaped like a Paper player connection. */
class ServerboundTapTest {

    private static final ActorAction ACTION =
            new ActorAction(ActorAction.Kind.USE_ON_BLOCK, new BlockPosition(1, 64, 2), Optional.empty());

    private final List<Object> looked = new ArrayList<>();
    private final List<Object> acted = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private boolean installed = true;
    private ActorEffects actorEffects = (channel, packet, action, handOver) -> {
        acted.add(packet);
        handOver.run();
    };

    @Test
    void everyPacketArrivesUnchangedAndOnce() {
        EmbeddedChannel channel = connection();

        channel.writeInbound("minecraft:swing", "minecraft:use_item_on", "not a packet");

        assertEquals("minecraft:swing", channel.readInbound());
        assertEquals("minecraft:use_item_on", channel.readInbound());
        assertEquals("not a packet", channel.readInbound());
        assertNull(channel.readInbound());
        assertEquals(List.of("minecraft:use_item_on"), acted);
        assertTrue(failures.isEmpty());
    }

    @Test
    void withoutCaptureNoDefinitionIsAsked() {
        installed = false;
        EmbeddedChannel channel = connection();

        channel.writeInbound("minecraft:use_item_on");

        assertEquals("minecraft:use_item_on", channel.readInbound());
        assertTrue(looked.isEmpty());
        assertTrue(acted.isEmpty());
    }

    @Test
    void aBlockActionIsHandedOverByTheActorEffects() {
        List<String> order = new ArrayList<>();
        actorEffects = (channel, packet, action, handOver) -> {
            order.add("before");
            handOver.run();
            order.add("after");
        };
        EmbeddedChannel channel = connection();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext context, Object message) {
                order.add("server");
            }
        });

        channel.writeInbound("minecraft:use_item_on");

        assertEquals(List.of("before", "server", "after"), order);
    }

    @Test
    void failuresAreReportedAndThePacketArrivesAnyway() {
        actorEffects = (channel, packet, action, handOver) -> {
            throw new IllegalStateException("broken effects");
        };
        EmbeddedChannel channel = connection();

        channel.writeInbound("minecraft:broken", "minecraft:unknown", "minecraft:use_item_on");

        assertEquals("minecraft:broken", channel.readInbound());
        assertEquals("minecraft:unknown", channel.readInbound());
        assertEquals("minecraft:use_item_on", channel.readInbound());
        assertNull(channel.readInbound());
        assertEquals(3, failures.size());
        assertInstanceOf(IncompatibleAdapterException.class, failures.get(1));
    }

    @Test
    void effectsThatFailAfterHandingOverDoNotHandOverTwice() {
        actorEffects = (channel, packet, action, handOver) -> {
            handOver.run();
            throw new IllegalStateException("broken after");
        };
        EmbeddedChannel channel = connection();

        channel.writeInbound("minecraft:use_item_on");

        assertEquals("minecraft:use_item_on", channel.readInbound());
        assertNull(channel.readInbound());
        assertEquals(1, failures.size());
    }

    @Test
    void theTapIsInstalledOnceBeforeTheServerConnection() {
        EmbeddedChannel channel = connection();
        ServerboundTap.install(channel.pipeline(), tap());

        List<String> names = channel.pipeline().names();
        assertEquals(names.indexOf(PipelineNames.PACKET_HANDLER) - 1, names.indexOf(PipelineNames.SERVERBOUND_TAP));
        assertEquals(1, names.stream().filter(PipelineNames.SERVERBOUND_TAP::equals).count());

        ServerboundTap.uninstall(channel.pipeline());
        assertTrue(!channel.pipeline().names().contains(PipelineNames.SERVERBOUND_TAP));
    }

    private EmbeddedChannel connection() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(PipelineNames.PACKET_HANDLER, new ChannelInboundHandlerAdapter());
        ServerboundTap.install(channel.pipeline(), tap());
        return channel;
    }

    /** Packets are strings holding their identifier; anything else is not a packet. */
    private ServerboundTap tap() {
        return new ServerboundTap(
                () -> installed,
                (channel, message) -> {
                    looked.add(message);
                    return message instanceof String text && text.startsWith("minecraft:")
                            ? Optional.of(text)
                            : Optional.empty();
                },
                new ServerboundPacketDefinitionCatalog(List.of(
                        new Definition(0, "minecraft:swing", ServerboundReaction.NONE),
                        new Definition(1, "minecraft:use_item_on", ServerboundReaction.actor(ACTION)),
                        new Definition(2, "minecraft:broken", null))),
                (channel, packet, action, handOver) -> actorEffects.around(channel, packet, action, handOver),
                failures::add);
    }

    /** Reacts with a fixed reaction, or fails when it has none. */
    private static final class Definition extends ServerboundPacketDefinition {

        private final ServerboundReaction reaction;

        Definition(int packetId, String identifier, ServerboundReaction reaction) {
            super(packetId, identifier);
            this.reaction = reaction;
        }

        @Override
        public ServerboundReaction react(Object decodedPacket) {
            if (reaction == null) {
                throw new IllegalStateException("broken definition");
            }
            return reaction;
        }
    }
}
