package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.api.recording.ChunkLoadingPolicy;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Resolves a public recording scope on the Paper main thread and turns the
 * selected live state into native, detached checkpoint packet values.
 */
public final class Paper26SnapshotProvider
        implements Paper26CheckpointEncoder.SnapshotProvider, AutoCloseable {

    private final MainThreadScheduler scheduler;
    private final Paper26WorldAccess worldAccess;
    private boolean closed;

    /** Creates a production provider bound to the owning Paper plugin. */
    public Paper26SnapshotProvider(JavaPlugin owner) {
        Objects.requireNonNull(owner, "owner");
        Server server = Objects.requireNonNull(owner.getServer(), "owner.server");
        this.scheduler = new BukkitMainThreadScheduler(owner, server);
        this.worldAccess = new LivePaper26WorldAccess(server);
    }

    /** Creates an adapter-local provider with explicit thread and world seams. */
    Paper26SnapshotProvider(
            MainThreadScheduler scheduler,
            Paper26WorldAccess worldAccess) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
    }

    @Override
    public CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot> snapshot(
            CheckpointEncoder.CheckpointRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Paper 26.2 snapshot provider is closed"));
            }
        }
        validateRequest(request);

        try {
            CompletionStage<CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot>> planned =
                    scheduler.submit(() -> resolveOnMainThread(request.scope()));
            return planned.thenCompose(stage -> Objects.requireNonNull(
                    stage,
                    "world resolution returned null stage"));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
    }

    private CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot> resolveOnMainThread(
            RecordingScope scope) {
        List<String> worlds = normalizedWorldKeys(worldAccess.worldKeys());
        Set<String> requestedWorlds = scope.worlds().stream()
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, List<ChunkBounds>> regions = regionsByWorld(scope);

        List<String> selectedWorlds = worlds.stream()
                .filter(world -> requestedWorlds.isEmpty() || requestedWorlds.contains(world))
                .filter(world -> regions.isEmpty() || regions.containsKey(world))
                .toList();

        if (scope.chunkLoadingPolicy() == ChunkLoadingPolicy.LOADED_ONLY) {
            List<ScopedChunk> selectedChunks = new ArrayList<>();
            for (String world : selectedWorlds) {
                for (ChunkCoordinate chunk : worldAccess.loadedChunks(world)) {
                    if (isInsideAnyRegion(world, chunk, regions)) {
                        selectedChunks.add(new ScopedChunk(world, chunk.x(), chunk.z()));
                    }
                }
            }
            selectedChunks.sort(ScopedChunk.ORDER);
            return CompletableFuture.completedFuture(
                    worldAccess.snapshot(scope, List.copyOf(deduplicate(selectedChunks))));
        }

        if (scope.regions().isEmpty()) {
            throw new IllegalArgumentException("PRELOAD_SCOPE requires bounded regions");
        }

        Map<String, List<ChunkCoordinate>> preloadByWorld = new HashMap<>();
        for (String world : selectedWorlds) {
            LinkedHashSet<ChunkCoordinate> chunks = new LinkedHashSet<>();
            for (ChunkBounds bounds : regions.getOrDefault(world, List.of())) {
                for (int x = bounds.minChunkX(); x <= bounds.maxChunkX(); x++) {
                    for (int z = bounds.minChunkZ(); z <= bounds.maxChunkZ(); z++) {
                        chunks.add(new ChunkCoordinate(x, z));
                    }
                }
            }
            List<ChunkCoordinate> ordered = chunks.stream()
                    .sorted(ChunkCoordinate.ORDER)
                    .toList();
            if (!ordered.isEmpty()) {
                preloadByWorld.put(world, ordered);
            }
        }

        List<CompletionStage<Void>> preloads = new ArrayList<>();
        for (Map.Entry<String, List<ChunkCoordinate>> entry : preloadByWorld.entrySet()) {
            preloads.add(Objects.requireNonNull(
                    worldAccess.preload(entry.getKey(), entry.getValue()),
                    "world preload returned null stage"));
        }
        CompletionStage<Void> allPreloads = allOf(preloads);
        List<ScopedChunk> selectedChunks = new ArrayList<>();
        for (Map.Entry<String, List<ChunkCoordinate>> entry : preloadByWorld.entrySet()) {
            for (ChunkCoordinate chunk : entry.getValue()) {
                selectedChunks.add(new ScopedChunk(entry.getKey(), chunk.x(), chunk.z()));
            }
        }
        selectedChunks.sort(ScopedChunk.ORDER);
        List<ScopedChunk> detachedChunks = List.copyOf(selectedChunks);

        return allPreloads.thenCompose(ignored -> scheduler.submit(
                () -> CompletableFuture.completedFuture(
                        worldAccess.snapshot(scope, detachedChunks)))).thenCompose(stage -> stage);
    }

    private static CompletionStage<Void> allOf(List<CompletionStage<Void>> stages) {
        if (stages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] futures = stages.stream()
                .map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private static List<ScopedChunk> deduplicate(List<ScopedChunk> chunks) {
        return new ArrayList<>(new LinkedHashSet<>(chunks));
    }

    private static Map<String, List<ChunkBounds>> regionsByWorld(RecordingScope scope) {
        Map<String, List<ChunkBounds>> regions = new HashMap<>();
        for (CuboidRegion region : scope.regions()) {
            regions.computeIfAbsent(region.world().toString(), ignored -> new ArrayList<>())
                    .add(ChunkBounds.from(region));
        }
        return regions;
    }

    private static boolean isInsideAnyRegion(
            String world,
            ChunkCoordinate chunk,
            Map<String, List<ChunkBounds>> regions) {
        List<ChunkBounds> bounds = regions.get(world);
        if (bounds == null) {
            return regions.isEmpty();
        }
        return bounds.stream().anyMatch(bound -> bound.contains(chunk));
    }

    private static List<String> normalizedWorldKeys(List<String> worldKeys) {
        Objects.requireNonNull(worldKeys, "worldKeys");
        List<String> normalized = new ArrayList<>(worldKeys.size());
        for (String worldKey : worldKeys) {
            normalized.add(stableText(worldKey, "worldKey"));
        }
        normalized.sort(String::compareTo);
        return List.copyOf(new LinkedHashSet<>(normalized));
    }

    private static void validateRequest(CheckpointEncoder.CheckpointRequest request) {
        if (request.elapsedNanos() < 0L || request.serverTick() < 0L) {
            throw new IllegalArgumentException("checkpoint timing must not be negative");
        }
    }

    private static String stableText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain whitespace or control characters");
            }
        }
        return value;
    }

    @FunctionalInterface
    interface MainThreadScheduler {
        CompletionStage<CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot>> submit(
                Supplier<CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot>> task);
    }

    interface Paper26WorldAccess {
        List<String> worldKeys();

        List<ChunkCoordinate> loadedChunks(String worldKey);

        CompletionStage<Void> preload(String worldKey, List<ChunkCoordinate> chunks);

        Paper26CheckpointEncoder.CheckpointSnapshot snapshot(
                RecordingScope scope,
                List<ScopedChunk> chunks);
    }

    record ChunkCoordinate(int x, int z) {
        private static final Comparator<ChunkCoordinate> ORDER = Comparator
                .comparingInt(ChunkCoordinate::x)
                .thenComparingInt(ChunkCoordinate::z);
    }

    record ScopedChunk(String worldKey, int x, int z) {
        private static final Comparator<ScopedChunk> ORDER = Comparator
                .comparing(ScopedChunk::worldKey)
                .thenComparingInt(ScopedChunk::x)
                .thenComparingInt(ScopedChunk::z);

        ScopedChunk {
            worldKey = stableText(worldKey, "worldKey");
        }
    }

    private record ChunkBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        private static ChunkBounds from(CuboidRegion region) {
            return new ChunkBounds(
                    Math.floorDiv(region.min().x(), 16),
                    Math.floorDiv(region.max().x(), 16),
                    Math.floorDiv(region.min().z(), 16),
                    Math.floorDiv(region.max().z(), 16));
        }

        private boolean contains(ChunkCoordinate chunk) {
            return chunk.x() >= minChunkX
                    && chunk.x() <= maxChunkX
                    && chunk.z() >= minChunkZ
                    && chunk.z() <= maxChunkZ;
        }
    }

    private static final class BukkitMainThreadScheduler implements MainThreadScheduler {
        private final JavaPlugin owner;
        private final Server server;

        private BukkitMainThreadScheduler(JavaPlugin owner, Server server) {
            this.owner = owner;
            this.server = server;
        }

        @Override
        public CompletionStage<CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot>> submit(
                Supplier<CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot>> task) {
            Objects.requireNonNull(task, "task");
            CompletableFuture<CompletionStage<Paper26CheckpointEncoder.CheckpointSnapshot>> result =
                    new CompletableFuture<>();
            Runnable invocation = () -> {
                try {
                    result.complete(task.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            };
            try {
                if (server.isPrimaryThread()) {
                    invocation.run();
                } else {
                    server.getScheduler().runTask(owner, invocation);
                }
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
            return result;
        }
    }

    private static final class LivePaper26WorldAccess implements Paper26WorldAccess {
        private final Server server;

        private LivePaper26WorldAccess(Server server) {
            this.server = server;
        }

        @Override
        public List<String> worldKeys() {
            return server.getWorlds().stream()
                    .map(world -> world.getKey().toString())
                    .toList();
        }

        @Override
        public List<ChunkCoordinate> loadedChunks(String worldKey) {
            World world = world(worldKey);
            return java.util.Arrays.stream(world.getLoadedChunks())
                    .map(chunk -> new ChunkCoordinate(chunk.getX(), chunk.getZ()))
                    .toList();
        }

        @Override
        public CompletionStage<Void> preload(
                String worldKey,
                List<ChunkCoordinate> chunks) {
            World world = world(worldKey);
            List<CompletableFuture<Chunk>> loads = chunks.stream()
                    .map(chunk -> world.getChunkAtAsync(chunk.x(), chunk.z(), true))
                    .toList();
            CompletableFuture<?>[] futures = loads.toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(futures);
        }

        @Override
        public Paper26CheckpointEncoder.CheckpointSnapshot snapshot(
                RecordingScope scope,
                List<ScopedChunk> chunks) {
            Map<String, Set<ChunkCoordinate>> selectedByWorld = new HashMap<>();
            for (ScopedChunk chunk : chunks) {
                selectedByWorld.computeIfAbsent(chunk.worldKey(), ignored -> new HashSet<>())
                        .add(new ChunkCoordinate(chunk.x(), chunk.z()));
            }

            List<Paper26CheckpointEncoder.PacketBlueprint> packets = new ArrayList<>();
            for (Map.Entry<String, Set<ChunkCoordinate>> entry : selectedByWorld.entrySet()) {
                String worldKey = entry.getKey();
                ServerLevel level = handle(worldKey);
                addGlobalPackets(packets, level, worldKey);
                for (ChunkCoordinate coordinate : entry.getValue()) {
                    LevelChunk chunk = level.getChunkIfLoaded(coordinate.x(), coordinate.z());
                    if (chunk == null) {
                        continue;
                    }
                    packets.add(blueprint(
                            new ClientboundLevelChunkWithLightPacket(
                                    chunk,
                                    level.getLightEngine(),
                                    new java.util.BitSet(),
                                    new java.util.BitSet()),
                            Paper26CheckpointEncoder.CheckpointPacketFamily.CHUNK_LIGHT,
                            worldKey,
                            "chunk:" + coordinate.x() + "," + coordinate.z()));
                    chunk.getBlockEntities().values().stream()
                            .map(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket::create)
                            .forEach(packet -> packets.add(blueprint(
                                    packet,
                                    Paper26CheckpointEncoder.CheckpointPacketFamily.BLOCK_ENTITY,
                                    worldKey,
                                    "block:" + packet.getPos().getX() + ","
                                            + packet.getPos().getY() + "," + packet.getPos().getZ())));
                }
                addEntityPackets(packets, level, worldKey, entry.getValue());
            }
            return new Paper26CheckpointEncoder.CheckpointSnapshot(packets);
        }

        private void addGlobalPackets(
                List<Paper26CheckpointEncoder.PacketBlueprint> packets,
                ServerLevel level,
                String worldKey) {
            packets.add(blueprint(
                    new ClientboundSetDefaultSpawnPositionPacket(level.getRespawnData()),
                    Paper26CheckpointEncoder.CheckpointPacketFamily.DIMENSION_SPAWN,
                    worldKey,
                    "spawn"));
            packets.add(blueprint(
                    new ClientboundChangeDifficultyPacket(
                            level.getLevelData().getDifficulty(),
                            level.getLevelData().isDifficultyLocked()),
                    Paper26CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                    worldKey,
                    "difficulty"));
            packets.add(blueprint(
                    new ClientboundInitializeBorderPacket(level.getWorldBorder()),
                    Paper26CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                    worldKey,
                    "world-border"));
            packets.add(blueprint(
                    new ClientboundSetTimePacket(level.getLevelData().getGameTime(), Map.of()),
                    Paper26CheckpointEncoder.CheckpointPacketFamily.GLOBAL_STATE,
                    worldKey,
                    "time"));
        }

        private void addEntityPackets(
                List<Paper26CheckpointEncoder.PacketBlueprint> packets,
                ServerLevel level,
                String worldKey,
                Set<ChunkCoordinate> chunks) {
            for (Entity entity : level.getAllEntities()) {
                ChunkCoordinate coordinate = new ChunkCoordinate(
                        Math.floorDiv((int) Math.floor(entity.getX()), 16),
                        Math.floorDiv((int) Math.floor(entity.getZ()), 16));
                if (!chunks.contains(coordinate)) {
                    continue;
                }
                String targetKey = "entity:" + entity.getId();
                packets.add(blueprint(
                        entity.getAddEntityPacket(new SnapshotServerEntity(level, entity)),
                        Paper26CheckpointEncoder.CheckpointPacketFamily.ENTITY_SPAWN,
                        worldKey,
                        targetKey));

                List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> metadata =
                        entity.getEntityData().getNonDefaultValues();
                if (!metadata.isEmpty()) {
                    packets.add(blueprint(
                            new ClientboundSetEntityDataPacket(entity.getId(), List.copyOf(metadata)),
                            Paper26CheckpointEncoder.CheckpointPacketFamily.ENTITY_METADATA,
                            worldKey,
                            targetKey));
                }
                if (!entity.getPassengers().isEmpty()) {
                    packets.add(blueprint(
                            new ClientboundSetPassengersPacket(entity),
                            Paper26CheckpointEncoder.CheckpointPacketFamily.PASSENGERS,
                            worldKey,
                            targetKey));
                }
                if (entity instanceof LivingEntity living) {
                    List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> equipment =
                            new ArrayList<>();
                    for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                        ItemStack item = living.getItemBySlot(slot);
                        if (!item.isEmpty()) {
                            equipment.add(com.mojang.datafixers.util.Pair.of(slot, item.copy()));
                        }
                    }
                    if (!equipment.isEmpty()) {
                        packets.add(blueprint(
                                new ClientboundSetEquipmentPacket(entity.getId(), equipment),
                                Paper26CheckpointEncoder.CheckpointPacketFamily.EQUIPMENT,
                                worldKey,
                                targetKey));
                    }
                    for (net.minecraft.world.effect.MobEffectInstance effect : living.getActiveEffects()) {
                        packets.add(blueprint(
                                new ClientboundUpdateMobEffectPacket(entity.getId(), effect, true),
                                Paper26CheckpointEncoder.CheckpointPacketFamily.RUNNING_EFFECT,
                                worldKey,
                                targetKey + ":effect"));
                    }
                }
            }
        }

        /**
         * Supplies the context required by entity-specific spawn packet
         * factories without connecting the snapshot to tracking players.
         */
        private static final class SnapshotServerEntity extends ServerEntity {
            private SnapshotServerEntity(ServerLevel level, Entity entity) {
                super(level, entity, 1, false, new Synchronizer() {
                    @Override
                    public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
                    }

                    @Override
                    public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
                    }

                    @Override
                    public void sendToTrackingPlayersFiltered(
                            Packet<? super ClientGamePacketListener> packet,
                            java.util.function.Predicate<ServerPlayer> predicate) {
                    }
                }, Set.of());
            }
        }

        private static Paper26CheckpointEncoder.PacketBlueprint blueprint(
                Packet<?> packet,
                Paper26CheckpointEncoder.CheckpointPacketFamily family,
                String worldKey,
                String targetKey) {
            String descriptor = "play.clientbound." + packet.type().id();
            return new Paper26CheckpointEncoder.PacketBlueprint(
                    descriptor,
                    family,
                    worldKey,
                    targetKey,
                    packet);
        }

        private World world(String worldKey) {
            net.kyori.adventure.key.Key key = net.kyori.adventure.key.Key.key(worldKey);
            return Objects.requireNonNull(server.getWorld(key), "Paper world " + worldKey);
        }

        private ServerLevel handle(String worldKey) {
            World world = world(worldKey);
            if (!(world instanceof CraftWorld craftWorld)) {
                throw new IllegalStateException("Paper world is not a CraftWorld");
            }
            return craftWorld.getHandle();
        }
    }
}
