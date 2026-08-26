package dev.voldechse.replayframework.example.viewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class ExampleViewerEnvironmentTest {

    @Test
    void copiesViewerConfigurationAndNormalizesCommandRoots() {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        Location location = new Location(world, 1.0, 2.0, 3.0, 90.0f, 10.0f);

        ExampleViewerEnvironment.Configuration configuration =
                new ExampleViewerEnvironment.Configuration(
                        location,
                        GameMode.ADVENTURE,
                        PlaybackBufferOptions.builder().build(),
                        Set.of("Replay"),
                        item -> false);

        location.setX(99.0);

        assertEquals(1.0, configuration.replayLocation().getX());
        assertEquals(Set.of("replay"), configuration.allowedCommandRoots());
        assertNotSame(configuration.replayLocation(), configuration.replayLocation());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExampleViewerEnvironment.Configuration(
                        new Location(null, 1.0, 2.0, 3.0),
                        GameMode.ADVENTURE,
                        PlaybackBufferOptions.builder().build(),
                        Set.of("/replay"),
                        item -> false));
    }

    @Test
    void reservesEachViewerOnlyOnceAndKeepsPendingOpenDuringRestore() {
        UUID viewerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ReplayId replayId = ReplayId.random();
        Player player = fakePlayer(viewerId);
        ViewerSessionRegistry registry = new ViewerSessionRegistry();

        ViewerSessionRegistry.ViewerSession entry = registry.reserve(player, replayId);
        assertEquals(ViewerSessionRegistry.State.PACK_PENDING, entry.state());
        assertThrows(IllegalStateException.class, () -> registry.reserve(player, replayId));

        CompletionStage<Object> pendingOpen = new CompletableFuture<>();
        registry.transition(entry, ViewerSessionRegistry.State.PACK_PENDING,
                ViewerSessionRegistry.State.PREPARING);
        registry.attachPlaybackOpen(entry, pendingOpen.thenApply(ignored -> null));
        registry.transition(entry, ViewerSessionRegistry.State.PREPARING,
                ViewerSessionRegistry.State.PLAYBACK_OPENING);

        CompletionStage<Void> firstRestore = registry.beginRestore(entry);
        CompletionStage<Void> secondRestore = registry.beginRestore(entry);

        assertSame(firstRestore, secondRestore);
        assertEquals(ViewerSessionRegistry.State.RESTORING, entry.state());
        assertTrue(registry.find(viewerId).isPresent());
        assertTrue(registry.removeIfSame(entry));
        assertFalse(registry.find(viewerId).isPresent());
    }

    @Test
    void capturesPlayerStateWithDefensiveCopiesAndRestoresOriginalValues() {
        UUID viewerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ItemStack[] originalContents = {null, null};
        FakePlayerState fake = new FakePlayerState(viewerId, originalContents);

        StoredPlayerState snapshot = StoredPlayerState.capture(fake.player());
        assertNotSame(originalContents, snapshot.contentsForTest());

        originalContents[0] = null;
        fake.contents.set(new ItemStack[]{null, null});

        snapshot.restore(fake.player());

        assertArrayEquals(
                new ItemStack[]{null, null},
                fake.contents.get());
        assertEquals(GameMode.SURVIVAL, fake.gameMode.get());
        assertEquals(20.0, fake.health.get());
    }

    @Test
    void protectsPreparedViewersAndKeepsPackPendingViewersUnprotected() {
        ViewerSessionRegistry registry = new ViewerSessionRegistry();
        Player prepared = fakePlayer(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        Player pending = fakePlayer(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        ViewerSessionRegistry.ViewerSession preparedEntry = registry.reserve(prepared, ReplayId.random());
        registry.transition(
                preparedEntry,
                ViewerSessionRegistry.State.PACK_PENDING,
                ViewerSessionRegistry.State.PREPARING);
        registry.reserve(pending, ReplayId.random());

        ViewerProtectionListener listener = new ViewerProtectionListener(
                registry,
                Set.of("replay"),
                item -> false,
                ignored -> { });
        Block block = (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));

        BlockBreakEvent breakEvent = new BlockBreakEvent(block, prepared);
        listener.onBlockBreak(breakEvent);
        assertTrue(breakEvent.isCancelled());

        PlayerInteractEvent interaction = new PlayerInteractEvent(
                prepared, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF);
        listener.onPlayerInteract(interaction);
        assertTrue(interaction.isCancelled());

        PlayerCommandPreprocessEvent allowedCommand = new PlayerCommandPreprocessEvent(
                prepared, "/replay open");
        listener.onCommand(allowedCommand);
        assertFalse(allowedCommand.isCancelled());

        PlayerCommandPreprocessEvent deniedCommand = new PlayerCommandPreprocessEvent(
                prepared, "/say hello");
        listener.onCommand(deniedCommand);
        assertTrue(deniedCommand.isCancelled());

        BlockBreakEvent pendingBreakEvent = new BlockBreakEvent(block, pending);
        listener.onBlockBreak(pendingBreakEvent);
        assertFalse(pendingBreakEvent.isCancelled());
    }

    private static Player fakePlayer(UUID id) {
        return new FakePlayerState(id, new ItemStack[]{null, null}).player();
    }

    private static final class FakePlayerState implements java.lang.reflect.InvocationHandler {
        private final UUID id;
        private final AtomicReference<ItemStack[]> contents;
        private final AtomicReference<GameMode> gameMode = new AtomicReference<>(GameMode.SURVIVAL);
        private final AtomicReference<Double> health = new AtomicReference<>(20.0);
        private final Server server;
        private final PlayerInventory inventory;
        private final Player player;

        private FakePlayerState(UUID id, ItemStack[] initialContents) {
            this.id = id;
            this.contents = new AtomicReference<>(cloneContents(initialContents));
            this.server = (Server) Proxy.newProxyInstance(
                    Server.class.getClassLoader(),
                    new Class<?>[]{Server.class},
                    (proxy, method, args) -> "getOnlinePlayers".equals(method.getName())
                            ? List.of()
                            : defaultValue(method.getReturnType()));
            this.inventory = (PlayerInventory) Proxy.newProxyInstance(
                    PlayerInventory.class.getClassLoader(),
                    new Class<?>[]{PlayerInventory.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getContents" -> cloneContents(this.contents.get());
                        case "setContents" -> {
                            this.contents.set(cloneContents((ItemStack[]) args[0]));
                            yield null;
                        }
                        case "getArmorContents" -> new ItemStack[4];
                        case "setArmorContents" -> null;
                        case "getItemInOffHand" -> null;
                        case "setItemInOffHand" -> null;
                        default -> defaultValue(method.getReturnType());
                    });
            this.player = (Player) Proxy.newProxyInstance(
                    Player.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    this);
        }

        private Player player() {
            return player;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getServer" -> server;
                case "getInventory" -> inventory;
                case "getLocation" -> new Location(null, 1, 2, 3);
                case "getGameMode" -> gameMode.get();
                case "setGameMode" -> {
                    gameMode.set((GameMode) args[0]);
                    yield null;
                }
                case "getHealth" -> health.get();
                case "setHealth" -> {
                    health.set((Double) args[0]);
                    yield null;
                }
                case "teleport" -> true;
                case "getFoodLevel" -> 20;
                case "setFoodLevel", "setFireTicks", "setFreezeTicks", "setLevel",
                        "setTotalExperience" -> null;
                case "getSaturation", "getExhaustion", "getExp" -> 0.0f;
                case "setSaturation", "setExhaustion", "setExp" -> null;
                case "getLevel", "getTotalExperience", "getFireTicks", "getFreezeTicks" -> 0;
                case "getAllowFlight", "isFlying", "isInvulnerable", "isInvisible",
                        "isGlowing", "isCollidable", "hasGravity" -> false;
                case "setAllowFlight", "setFlying", "setInvulnerable", "setInvisible",
                        "setGlowing", "setCollidable", "setGravity" -> null;
                case "getActivePotionEffects" -> List.of();
                case "removePotionEffect" -> null;
                case "addPotionEffect" -> true;
                case "getItemOnCursor" -> null;
                case "setItemOnCursor" -> null;
                case "getMaxHealth" -> 20.0;
                case "equals" -> proxy == args[0];
                case "hashCode" -> id.hashCode();
                case "toString" -> "FakePlayer[" + id + "]";
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        return Arrays.stream(contents)
                .map(item -> item == null ? null : item.clone())
                .toArray(ItemStack[]::new);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        return null;
    }
}
