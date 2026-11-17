package dev.voldechse.replayframework.adapter.paper.v26_2.capture;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;
import dev.voldechse.replayframework.adapter.paper.v26_2.actor.Paper26_2ActorEffects;
import dev.voldechse.replayframework.adapter.paper.v26_2.packet.serverbound.Paper26_2ServerboundPacketCatalog;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Hands every received PLAY packet to its serverbound definition before the
 * server queues it.
 *
 * <p>The handler sits directly before the server's {@code packet_handler}
 * and sees the decoded packet object. It never changes, drops or delays a
 * packet; it only lets the definition decide what the packet triggers.</p>
 */
final class Paper26_2ServerboundReceiver extends ChannelInboundHandlerAdapter {

    /** Pipeline name of this handler. */
    static final String HANDLER_NAME = "replay_framework_serverbound";
    /** Pipeline name of the server's {@link Connection}. */
    static final String SERVER_HANDLER_NAME = "packet_handler";

    private static final String TYPE_PREFIX = "play.serverbound.";

    private final Connection connection;
    private final BooleanSupplier installed;
    private final Paper26_2ActorEffects actorEffects;
    private final Consumer<Throwable> failureHandler;

    Paper26_2ServerboundReceiver(
            Connection connection,
            BooleanSupplier installed,
            Paper26_2ActorEffects actorEffects,
            Consumer<Throwable> failureHandler) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.installed = Objects.requireNonNull(installed, "installed");
        this.actorEffects = Objects.requireNonNull(actorEffects, "actorEffects");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!installed.getAsBoolean()
                || !(message instanceof Packet<?> packet)
                || !(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            context.fireChannelRead(message);
            return;
        }
        ServerboundReaction reaction;
        try {
            reaction = definition(packet).react(packet);
        } catch (Throwable failure) {
            failureHandler.accept(failure);
            context.fireChannelRead(message);
            return;
        }
        if (reaction.actorAction().isPresent()) {
            actorEffects.around(
                    listener, packet, reaction.actorAction().get(),
                    () -> context.fireChannelRead(message));
        } else {
            context.fireChannelRead(message);
        }
    }

    private static ServerboundPacketDefinition definition(Packet<?> packet) {
        String typeName = TYPE_PREFIX + packet.type().id();
        return Paper26_2ServerboundPacketCatalog.catalog().find(typeName)
                .orElseThrow(() -> new IncompatibleAdapterException(
                        "PLAY serverbound packet has no definition: " + typeName));
    }
}
