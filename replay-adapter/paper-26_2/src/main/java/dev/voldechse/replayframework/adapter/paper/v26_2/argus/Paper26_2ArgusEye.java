package dev.voldechse.replayframework.adapter.paper.v26_2.argus;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import dev.voldechse.replayframework.adapter.paper.argus.ArgusEye;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;

/**
 * A packet recipient for Paper 26.2 that no player, entity or plugin lookup
 * can reach.
 *
 * <p>The observer is a {@link ServerPlayer} object that is never added to its
 * level and never added to the player list. It is registered only with the
 * two systems that decide who receives scene packets: the Moonrise chunk
 * loader, which sends chunks and block changes, and the nearby-player map
 * the entity tracker pairs entities from. Because it is not an entity, the
 * tracker never sends it to anyone, it has no hitbox, mobs cannot target it,
 * selectors and the Bukkit world do not list it, and it is absent from the
 * tab list, the player count and every player-list broadcast.</p>
 *
 * <p>Its connection has no socket. Every packet the server sends is handed
 * to the supplied consumer on the server thread, in server order.</p>
 *
 * <p>All methods must be called on the server thread.</p>
 */
public final class Paper26_2ArgusEye implements ArgusEye {

    private static final Method LOADER_UPDATE = loaderMethod("update");
    private static final Method LOADER_UPDATE_QUEUES = loaderMethod("updateQueues", long.class);
    private static final Method SET_GAME_MODE = gameModeMethod();

    private final ServerLevel level;
    private final ServerPlayer player;
    private final ObserverConnection connection;
    private boolean closed;

    private Paper26_2ArgusEye(
            ServerLevel level, ServerPlayer player, ObserverConnection connection) {
        this.level = level;
        this.player = player;
        this.connection = connection;
    }

    /**
     * Creates an observer at a fixed position and starts receiving the scene
     * around it.
     *
     * @param level level to observe
     * @param x block-space X of the observation point
     * @param y block-space Y of the observation point
     * @param z block-space Z of the observation point
     * @param viewDistance chunk radius to load, send and keep simulated
     * @param sink receives every packet sent to the observer, on the server thread
     */
    public static Paper26_2ArgusEye open(
            ServerLevel level,
            double x,
            double y,
            double z,
            int viewDistance,
            Consumer<? super Packet<?>> sink) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(sink, "sink");
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("viewDistance must be between 2 and 32");
        }
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "argus-eye");
        ServerPlayer player = new ServerPlayer(
                server, level, profile, ClientInformation.createDefault());
        invoke(SET_GAME_MODE, player.gameMode, GameType.SPECTATOR, null);
        player.snapTo(x, y, z, 0.0F, 0.0F);

        // The listener constructor assigns itself to player.connection.
        ObserverConnection connection = new ObserverConnection(sink);
        new ServerGamePacketListenerImpl(
                server,
                connection,
                player,
                CommonListenerCookie.createInitial(profile, false));

        player.moonrise$setRealPlayer(true);
        player.moonrise$getViewDistanceHolder().updateViewDistance(distances -> distances
                .setLoadViewDistance(viewDistance + 1)
                .setSendViewDistance(viewDistance)
                .setTickViewDistance(viewDistance));

        ChunkSystemServerLevel chunkSystem = (ChunkSystemServerLevel) level;
        chunkSystem.moonrise$getNearbyPlayers().addPlayer(player);
        chunkSystem.moonrise$getPlayerChunkLoader().addPlayer(player);
        return new Paper26_2ArgusEye(level, player, connection);
    }

    /**
     * Advances chunk loading and sending. The level only ticks the chunk
     * loaders of players in its player list, so the owner drives this once
     * per server tick.
     */
    @Override
    public void tick() {
        if (closed) {
            return;
        }
        // A level without listed players stops ticking its entities after
        // 300 ticks. The observed scene must keep running without them.
        level.resetEmptyTime();
        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader = player.moonrise$getChunkLoader();
        if (loader == null) {
            return;
        }
        invoke(LOADER_UPDATE, loader);
        invoke(LOADER_UPDATE_QUEUES, loader, System.nanoTime());
    }

    /** Moves the observation point; chunks and entities follow on the next ticks. */
    @Override
    public void moveTo(double x, double y, double z) {
        if (closed) {
            return;
        }
        player.snapTo(x, y, z, 0.0F, 0.0F);
        ((ChunkSystemServerLevel) level).moonrise$getNearbyPlayers().tickPlayer(player);
    }

    /** Unique profile ID of this observer; it never appears in any server listing. */
    public UUID id() {
        return player.getUUID();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Deregistering unloads the observer's whole view. Those packets
        // describe the observer going away, not the scene.
        connection.muted = true;
        ChunkSystemServerLevel chunkSystem = (ChunkSystemServerLevel) level;
        chunkSystem.moonrise$getPlayerChunkLoader().removePlayer(player);
        chunkSystem.moonrise$getNearbyPlayers().removePlayer(player);
        for (ChunkMap.TrackedEntity tracked : level.getChunkSource().chunkMap.entityMap.values()) {
            tracked.removePlayer(player);
        }
    }

    private static Method loaderMethod(String name, Class<?>... parameters) {
        try {
            Method method = RegionizedPlayerChunkLoader.PlayerChunkLoaderData.class
                    .getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Method gameModeMethod() {
        try {
            Method method = ServerPlayerGameMode.class.getDeclaredMethod(
                    "setGameModeForPlayer", GameType.class, GameType.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void invoke(Method method, Object target, Object... arguments) {
        try {
            method.invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Paper 26.2 virtual observer could not call " + method.getName(), exception);
        }
    }

    /** A connection without a socket that hands outbound packets to the capture sink. */
    private static final class ObserverConnection extends Connection {

        private final Consumer<? super Packet<?>> sink;
        private boolean muted;

        private ObserverConnection(Consumer<? super Packet<?>> sink) {
            super(PacketFlow.SERVERBOUND);
            this.sink = sink;
            // Server code treats a connection without an open channel as
            // disconnected. The embedded channel is open but never written to.
            this.channel = new EmbeddedChannel();
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            if (!muted) {
                sink.accept(packet);
            }
        }

        @Override
        public void flushChannel() {
        }
    }
}
