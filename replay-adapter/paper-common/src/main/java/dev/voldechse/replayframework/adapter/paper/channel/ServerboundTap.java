package dev.voldechse.replayframework.adapter.paper.channel;

import dev.voldechse.replayframework.adapter.paper.actor.ActorEffects;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.packet.serverbound.ServerboundReaction;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Hands every PLAY packet one player connection receives to its serverbound
 * definition before the server queues it.
 *
 * <p>The tap sits directly before {@link PipelineNames#PACKET_HANDLER} and
 * sees the decoded packet object. It never changes, drops or delays a
 * packet; it only lets the definition decide what the packet triggers. A
 * block action goes through the {@link ActorEffects}, which pass the packet
 * on themselves. Every failure is reported and the packet passes on
 * regardless.</p>
 */
public final class ServerboundTap extends ChannelInboundHandlerAdapter {

    private final BooleanSupplier installed;
    private final BiFunction<Channel, Object, Optional<String>> identifier;
    private final ServerboundPacketDefinitionCatalog definitions;
    private final ActorEffects actorEffects;
    private final Consumer<Throwable> failures;

    /**
     * @param installed whether capture is installed; otherwise every packet just passes
     * @param identifier the PLAY packet identifier of a received message, such as
     *                   {@code minecraft:use_item_on}; empty when the message is not
     *                   a PLAY packet of the version or the connection is not in game
     * @param definitions the version's serverbound definitions
     * @param actorEffects derives the effects of block actions
     * @param failures receives failures; the packet passes on regardless
     */
    public ServerboundTap(
            BooleanSupplier installed,
            BiFunction<Channel, Object, Optional<String>> identifier,
            ServerboundPacketDefinitionCatalog definitions,
            ActorEffects actorEffects,
            Consumer<Throwable> failures) {
        this.installed = Objects.requireNonNull(installed, "installed");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.actorEffects = Objects.requireNonNull(actorEffects, "actorEffects");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /** Adds the tap to a pipeline once; does nothing when it is there or the pipeline has no server connection. */
    public static void install(ChannelPipeline pipeline, ServerboundTap tap) {
        if (pipeline.get(PipelineNames.SERVERBOUND_TAP) == null
                && pipeline.get(PipelineNames.PACKET_HANDLER) != null) {
            pipeline.addBefore(PipelineNames.PACKET_HANDLER, PipelineNames.SERVERBOUND_TAP, tap);
        }
    }

    /** Removes the tap from a pipeline if it is there. */
    public static void uninstall(ChannelPipeline pipeline) {
        if (pipeline.get(PipelineNames.SERVERBOUND_TAP) != null) {
            pipeline.remove(PipelineNames.SERVERBOUND_TAP);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        Runnable handOver = () -> context.fireChannelRead(message);
        if (!installed.getAsBoolean()) {
            handOver.run();
            return;
        }
        ServerboundReaction reaction;
        try {
            Optional<String> packetIdentifier = identifier.apply(context.channel(), message);
            if (packetIdentifier.isEmpty()) {
                handOver.run();
                return;
            }
            reaction = definitions.require(ServerboundPacketDefinition.typeName(packetIdentifier.get()))
                    .react(message);
        } catch (Throwable failure) {
            failures.accept(failure);
            handOver.run();
            return;
        }
        if (reaction.actorAction().isEmpty()) {
            handOver.run();
            return;
        }
        boolean[] handedOver = {false};
        try {
            actorEffects.around(context.channel(), message, reaction.actorAction().get(), () -> {
                handedOver[0] = true;
                handOver.run();
            });
        } catch (Throwable failure) {
            failures.accept(failure);
            if (!handedOver[0]) {
                handOver.run();
            }
        }
    }
}
