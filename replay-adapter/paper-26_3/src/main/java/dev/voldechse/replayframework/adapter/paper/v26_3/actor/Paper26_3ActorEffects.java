package dev.voldechse.replayframework.adapter.paper.v26_3.actor;

import dev.voldechse.replayframework.adapter.paper.actor.ActorAction;
import dev.voldechse.replayframework.adapter.paper.actor.ActorBlockEffects;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffects;
import dev.voldechse.replayframework.adapter.paper.actor.ActorEffect;
import dev.voldechse.replayframework.adapter.paper.channel.PipelineNames;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Derives the block effects the server sends to everyone except the acting
 * player.
 *
 * <p>For each action it queues one task directly before and one directly
 * after the player's packet in the server's packet queue (see
 * {@link QueuedServerTask}). The first reads the probed blocks, the second
 * reads them again and lets the effect families explain the change.</p>
 *
 * <p>A derived effect is published only when no other player would have
 * received the original, using the same test as the server's
 * {@code PlayerList.broadcast}. Otherwise that player's connection delivers
 * the real packet and the derived one would be a duplicate.</p>
 */
public final class Paper26_3ActorEffects implements ActorEffects {

    private final ActorBlockEffects<BlockState> families = new ActorBlockEffects<>(List.of(
            new HingedBlockEffect(),
            new SwitchEffect(),
            new BlockBreakEffect(),
            new BlockPlaceEffect()));
    private final BooleanSupplier active;
    private final BiConsumer<Object, UUID> publisher;
    private final Consumer<Throwable> failureHandler;

    /**
     * @param active whether a recording consumes capture data
     * @param publisher publishes a derived clientbound packet for the acting
     *                  player's connection, on the server thread
     * @param failureHandler receives failures instead of the server
     */
    public Paper26_3ActorEffects(
            BooleanSupplier active,
            BiConsumer<Object, UUID> publisher,
            Consumer<Throwable> failureHandler) {
        this.active = Objects.requireNonNull(active, "active");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public void around(Channel channel, Object packet, ActorAction action, Runnable handOver) {
        if (!active.getAsBoolean()
                || !(packet instanceof Packet<?> nativePacket)
                || !(channel.pipeline().get(PipelineNames.PACKET_HANDLER) instanceof Connection connection)
                || !(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)) {
            handOver.run();
            return;
        }
        Probe probe = new Probe(listener, action);
        QueuedServerTask.queue(listener, nativePacket, () -> guarded(probe::readBefore));
        handOver.run();
        QueuedServerTask.queue(listener, nativePacket, () -> guarded(probe::readAfterAndPublish));
    }

    private void guarded(Runnable task) {
        try {
            task.run();
        } catch (Throwable failure) {
            failureHandler.accept(failure);
        }
    }

    private static Map<BlockPosition, BlockState> read(ServerLevel level, ActorAction action) {
        Map<BlockPosition, BlockState> states = new HashMap<>();
        for (BlockPosition position : action.probedPositions()) {
            BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
            // Never load a chunk for a recording.
            if (level.isLoaded(pos)) {
                states.put(position, level.getBlockState(pos));
            }
        }
        return states;
    }

    /** Mirrors the recipient test of {@code PlayerList.broadcast} with the actor as exception. */
    private static boolean anotherPlayerReceives(ServerPlayer actor, ActorEffect effect) {
        ServerLevel level = actor.level();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player == actor || player.level().dimension() != level.dimension()) {
                continue;
            }
            double dx = effect.x() - player.getX();
            double dy = effect.y() - player.getY();
            double dz = effect.z() - player.getZ();
            if (dx * dx + dy * dy + dz * dz < effect.range() * effect.range()
                    && player.getBukkitEntity().canSee(actor.getBukkitEntity())) {
                return true;
            }
        }
        return false;
    }

    /** Before and after reads of one action, both on the server thread. */
    private final class Probe {
        private final ServerGamePacketListenerImpl listener;
        private final ActorAction action;
        private ServerLevel level;
        private Map<BlockPosition, BlockState> before;

        private Probe(ServerGamePacketListenerImpl listener, ActorAction action) {
            this.listener = listener;
            this.action = action;
        }

        private void readBefore() {
            level = listener.player.level();
            before = read(level, action);
        }

        private void readAfterAndPublish() {
            ServerPlayer actor = listener.player;
            if (before == null || actor.level() != level || !active.getAsBoolean()) {
                return;
            }
            for (ActorEffect effect : families.effects(action, before, read(level, action))) {
                if (!anotherPlayerReceives(actor, effect)) {
                    publisher.accept(effect.packet(), actor.getUUID());
                }
            }
        }
    }
}
