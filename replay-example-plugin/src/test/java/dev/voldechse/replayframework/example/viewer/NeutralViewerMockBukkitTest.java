package dev.voldechse.replayframework.example.viewer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voldechse.replayframework.api.id.ReplayId;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Server API tests shared by the 26.2 and 26.3 MockBukkit test runs. */
class NeutralViewerMockBukkitTest {
    private ServerMock server;
    private PlayerMock observer;
    private PlayerMock other;
    private ViewerSessionRegistry registry;
    private ViewerSessionRegistry.ViewerSession session;
    private ViewerProtectionListener listener;

    @BeforeEach
    void setUp() {
        // MockBukkit reports unimplemented operations as skipped tests. These
        // acceptance checks deliberately turn such an operation into a failure.
        assertDoesNotThrow(() -> {
            server = MockBukkit.mock();
            observer = server.addPlayer("Observer");
            other = server.addPlayer("OtherPlayer");
            registry = new ViewerSessionRegistry();
            session = registry.reserve(observer, ReplayId.random());
            assertTrue(registry.transition(session, ViewerSessionRegistry.State.PACK_PENDING,
                    ViewerSessionRegistry.State.PREPARING));
            listener = new ViewerProtectionListener(registry, Set.of("replay"), item -> false, player -> {});
            listener.register(server.getPluginManager(), new ListenerPlugin(server));
        });
    }

    /** Uses PluginMock's direct fixture constructor; no generated plugin subclass is needed. */
    @SuppressWarnings("deprecation")
    private static final class ListenerPlugin extends org.mockbukkit.mockbukkit.plugin.PluginMock {
        ListenerPlugin(ServerMock server) throws java.io.IOException {
            super(new org.bukkit.plugin.java.JavaPluginLoader(server),
                    new org.bukkit.plugin.PluginDescriptionFile("ViewerTest", "1.0", ListenerPlugin.class.getName()),
                    java.nio.file.Files.createTempDirectory("viewer-test").toFile(), new java.io.File("viewer-test.jar"));
            setEnabled(true);
        }
    }

    @AfterEach
    void tearDown() {
        if (listener != null) listener.unregister();
        MockBukkit.unmock();
    }

    @Test
    void observerCannotTakeDamageButOtherPlayersRemainUnaffected() {
        assertDoesNotThrow(() -> {
            observer.setHealth(18);
            observer.simulateDamage(5, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build());
            assertEquals(18, observer.getHealth());
            other.setHealth(18);
            other.simulateDamage(5, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build());
            assertEquals(13, other.getHealth());
            registry.removeIfSame(session);
            observer.damage(5);
            assertEquals(13, observer.getHealth(), "Protection must end with the viewer session");
        });
    }

    @Test
    void observerCannotBreakTheLiveWorld() {
        assertDoesNotThrow(() -> {
            var block = observer.getWorld().getBlockAt(4, 64, 4);
            block.setType(Material.STONE);
            assertTrue(observer.simulateBlockBreak(block).isCancelled());
            assertEquals(Material.STONE, block.getType());
            assertFalse(other.simulateBlockBreak(block).isCancelled());
            assertEquals(Material.AIR, block.getType());
        });
    }

    @Test
    void onlyReplayCommandsPassWhileProtectedAndListenerRemovalRestoresNormalHandling() {
        assertDoesNotThrow(() -> {
            var blocked = new PlayerCommandPreprocessEvent(observer, "/give Observer diamond");
            server.getPluginManager().callEvent(blocked);
            assertTrue(blocked.isCancelled());
            var control = new PlayerCommandPreprocessEvent(observer, "/replay stop");
            server.getPluginManager().callEvent(control);
            assertFalse(control.isCancelled());
            listener.unregister();
            var after = new PlayerCommandPreprocessEvent(observer, "/give Observer diamond");
            server.getPluginManager().callEvent(after);
            assertFalse(after.isCancelled());
        });
    }

    @Test
    void restoresInventoryVitalsPositionEffectsAndScoreboardAfterLeavingTheViewerEnvironment() {
        assertDoesNotThrow(() -> {
            var original = new Location(observer.getWorld(), 10, 80, -5, 35, 15);
            observer.teleport(original);
            observer.setGameMode(GameMode.SURVIVAL);
            observer.setHealth(17);
            observer.setFoodLevel(13);
            observer.setSaturation(3);
            observer.setLevel(21);
            observer.setExp(0.4f);
            observer.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
            observer.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            observer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));
            var scoreboard = server.getScoreboardManager().getNewScoreboard();
            observer.setScoreboard(scoreboard);
            var stored = StoredPlayerState.capture(observer);
            observer.teleport(new Location(observer.getWorld(), 100, 90, 100));
            observer.setGameMode(GameMode.SPECTATOR);
            observer.setHealth(3);
            observer.setFoodLevel(1);
            observer.setSaturation(0);
            observer.setLevel(2);
            observer.setExp(0);
            observer.getInventory().clear();
            observer.removePotionEffect(PotionEffectType.SPEED);
            observer.setScoreboard(server.getScoreboardManager().getNewScoreboard());
            stored.restore(observer);
            assertEquals(original, observer.getLocation());
            assertEquals(GameMode.SURVIVAL, observer.getGameMode());
            assertEquals(17, observer.getHealth());
            assertEquals(13, observer.getFoodLevel());
            assertEquals(3, observer.getSaturation());
            assertEquals(21, observer.getLevel());
            assertEquals(0.4f, observer.getExp());
            assertEquals(new ItemStack(Material.DIAMOND, 5), observer.getInventory().getItem(0));
            assertEquals(Material.SHIELD, observer.getInventory().getItemInOffHand().getType());
            assertEquals(1, observer.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
            assertSame(scoreboard, observer.getScoreboard());
        });
    }
}
