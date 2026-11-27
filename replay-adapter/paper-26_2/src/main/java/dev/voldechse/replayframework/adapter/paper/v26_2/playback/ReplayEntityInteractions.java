package dev.voldechse.replayframework.adapter.paper.v26_2.playback;

import dev.voldechse.replayframework.adapter.playback.ViewerInteractions;

import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import org.bukkit.craftbukkit.CraftEquipmentSlot;

/**
 * Reads a replay viewer's interactions with replay entities and recorded
 * blocks from its serverbound packets.
 *
 * <p>The handler sits directly before the server's {@code packet_handler}
 * of the viewer's connection and sees the decoded packets. A use or attack
 * packet aimed at an entity of the replay, and a use of a block of the
 * replay whose contents are recorded, such as a chest, are handed to the
 * playback and kept from the server, which knows neither. Every other packet
 * passes unchanged. The client sends a block use once per hand; only the main
 * hand's is reported.</p>
 */
final class ReplayEntityInteractions extends ChannelInboundHandlerAdapter {

    /** Pipeline name of this handler. */
    static final String HANDLER_NAME = "replay-framework-playback-interactions";
    /** Pipeline name of the server's connection handler. */
    static final String SERVER_HANDLER_NAME = "packet_handler";

    private final IntFunction<Optional<RecordedEntity>> entities;
    private final Consumer<ViewerInteractions.EntityInteraction> interactions;
    private final java.util.function.BiFunction<net.minecraft.core.BlockPos, org.bukkit.inventory.EquipmentSlot,
            Optional<ViewerInteractions.BlockInteraction>> blocks;
    private final Consumer<ViewerInteractions.BlockInteraction> blockInteractions;
    private final Consumer<Throwable> failureHandler;

    /**
     * @param entities what the scene shows of the entity with a client entity ID
     * @param interactions receives the interactions
     * @param blocks the click on a recorded block at a position, when there is one
     * @param blockInteractions receives the clicks on recorded blocks
     * @param failureHandler receives failures; the packet then passes on
     */
    ReplayEntityInteractions(
            IntFunction<Optional<RecordedEntity>> entities,
            Consumer<ViewerInteractions.EntityInteraction> interactions,
            java.util.function.BiFunction<net.minecraft.core.BlockPos, org.bukkit.inventory.EquipmentSlot,
                    Optional<ViewerInteractions.BlockInteraction>> blocks,
            Consumer<ViewerInteractions.BlockInteraction> blockInteractions,
            Consumer<Throwable> failureHandler) {
        this.entities = Objects.requireNonNull(entities, "entities");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.blockInteractions = Objects.requireNonNull(blockInteractions, "blockInteractions");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket use) {
            useBlock(context, use);
            return;
        }
        int entityId;
        boolean attack;
        InteractionHand hand;
        if (message instanceof ServerboundInteractPacket interact) {
            entityId = interact.entityId();
            attack = false;
            hand = interact.hand();
        } else if (message instanceof ServerboundAttackPacket attackPacket) {
            entityId = attackPacket.entityId();
            attack = true;
            hand = InteractionHand.MAIN_HAND;
        } else {
            context.fireChannelRead(message);
            return;
        }
        try {
            Optional<RecordedEntity> entity = entities.apply(entityId);
            if (entity.isEmpty()) {
                context.fireChannelRead(message);
                return;
            }
            interactions.accept(new ViewerInteractions.EntityInteraction(
                    entity.get(), attack, CraftEquipmentSlot.getHand(hand)));
        } catch (Throwable failure) {
            failureHandler.accept(failure);
            context.fireChannelRead(message);
        }
    }

    /** Consumes the use of a recorded block, such as a chest of the replay. */
    private void useBlock(ChannelHandlerContext context, net.minecraft.network.protocol.game.ServerboundUseItemOnPacket use) {
        try {
            org.bukkit.inventory.EquipmentSlot hand = CraftEquipmentSlot.getHand(use.getHand());
            Optional<ViewerInteractions.BlockInteraction> interaction =
                    blocks.apply(use.getHitResult().getBlockPos(), hand);
            if (interaction.isEmpty()) {
                context.fireChannelRead(use);
                return;
            }
            if (use.getHand() == InteractionHand.MAIN_HAND) {
                blockInteractions.accept(interaction.get());
            }
        } catch (Throwable failure) {
            failureHandler.accept(failure);
            context.fireChannelRead(use);
        }
    }
}
