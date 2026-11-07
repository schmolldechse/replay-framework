package dev.voldechse.replayframework.runtime;

import dev.voldechse.replayframework.core.playback.ObserverPlacementService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread Paper implementation of safe asynchronous observer placement. */
public final class PaperObserverPlacementService implements ObserverPlacementService {
    private static final double POSITION_EPSILON_SQUARED = 0.01D;
    private final JavaPlugin plugin;

    public PaperObserverPlacementService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CompletionStage<Void> place(Player viewer, List<Location> candidates) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(candidates, "candidates");
        List<Location> copy = new ArrayList<>(candidates.size());
        for (Location candidate : candidates) {
            copy.add(Objects.requireNonNull(candidate, "candidate").clone());
        }
        if (copy.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("observer candidates must not be empty"));
        }
        return onMain(() -> {
            validateViewer(viewer);
            return tryCandidate(viewer, copy, 0);
        }).thenCompose(stage -> stage);
    }

    private CompletionStage<Void> tryCandidate(
            Player viewer,
            List<Location> candidates,
            int index) {
        if (index >= candidates.size()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Replay contains no safe observer position"));
        }
        Location target = candidates.get(index);
        try {
            validateViewer(viewer);
            validateBounds(target);
        } catch (RuntimeException rejected) {
            return tryCandidate(viewer, candidates, index + 1);
        }

        World world = target.getWorld();
        return world.getChunkAtAsync(target, true)
                .<CompletionStage<Void>>handle((chunk, failure) -> {
                    if (failure != null) {
                        return tryCandidate(viewer, candidates, index + 1);
                    }
                    try {
                        validateViewer(viewer);
                        validateSafeVolume(target);
                    } catch (RuntimeException rejected) {
                        return tryCandidate(viewer, candidates, index + 1);
                    }
                    return viewer.teleportAsync(
                                    target,
                                    PlayerTeleportEvent.TeleportCause.PLUGIN)
                            .thenCompose(success -> {
                                if (!success) {
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException(
                                                    "Observer teleportation was rejected"));
                                }
                                validateViewer(viewer);
                                Location actual = viewer.getLocation();
                                validateBounds(actual);
                                validateSafeVolume(actual);
                                if (actual.getWorld() != target.getWorld()
                                        || actual.distanceSquared(target)
                                        > POSITION_EPSILON_SQUARED) {
                                    return CompletableFuture.failedFuture(
                                            new IllegalStateException(
                                                    "Observer teleport destination was changed"));
                                }
                                return CompletableFuture.completedFuture(null);
                            });
                })
                .thenCompose(stage -> stage);
    }

    private static void validateViewer(Player viewer) {
        if (!viewer.isOnline()) {
            throw new IllegalStateException("Replay observer is offline");
        }
        if (viewer.isInsideVehicle() || !viewer.getPassengers().isEmpty()) {
            throw new IllegalStateException(
                    "Replay observer must not have a vehicle or passengers");
        }
    }

    private static void validateBounds(Location target) {
        if (!target.isWorldLoaded() || target.getWorld() == null) {
            throw new IllegalArgumentException("Observer target world is not loaded");
        }
        target.checkFinite();
        if (!Float.isFinite(target.getYaw()) || !Float.isFinite(target.getPitch())) {
            throw new IllegalArgumentException("Observer rotation must be finite");
        }
        World world = target.getWorld();
        int feetY = target.getBlockY();
        if (feetY < world.getMinHeight() || feetY + 1 >= world.getMaxHeight()) {
            throw new IllegalArgumentException("Observer target is outside build height");
        }
        if (!world.getWorldBorder().isInside(target)) {
            throw new IllegalArgumentException("Observer target is outside the world border");
        }
    }

    private static void validateSafeVolume(Location target) {
        Block feet = target.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        if (!isSafe(feet) || !isSafe(head)) {
            throw new IllegalArgumentException("Observer target intersects an unsafe block");
        }
    }

    private static boolean isSafe(Block block) {
        Material type = block.getType();
        return block.isPassable()
                && !block.isLiquid()
                && type != Material.FIRE
                && type != Material.SOUL_FIRE
                && type != Material.CACTUS
                && type != Material.MAGMA_BLOCK
                && type != Material.CAMPFIRE
                && type != Material.SOUL_CAMPFIRE
                && type != Material.SWEET_BERRY_BUSH
                && type != Material.POWDER_SNOW;
    }

    private <T> CompletionStage<T> onMain(Supplier<T> action) {
        if (plugin.getServer().isPrimaryThread()) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }
}
