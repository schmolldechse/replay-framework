package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/** Single Paper event boundary for browser inventories and playback controls. */
public final class ReplayBrowserListener implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ReplayBrowser browser;
    private final PlaybackHotbar hotbar;
    private final ExampleViewerEnvironment environment;
    private final ExampleItemModels models;
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();

    public ReplayBrowserListener(
            JavaPlugin plugin,
            ReplayBrowser browser,
            PlaybackHotbar hotbar,
            ExampleViewerEnvironment environment,
            ExampleItemModels models) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.browser = Objects.requireNonNull(browser, "browser");
        this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.models = Objects.requireNonNull(models, "models");
    }

    /** Registers the listener at most once. */
    public void register() {
        if (closing.get()) {
            return;
        }
        if (registered.compareAndSet(false, true)) {
            PluginManager pluginManager = plugin.getServer().getPluginManager();
            pluginManager.registerEvents(this, plugin);
        }
    }

    @Override
    public void close() {
        if (closing.compareAndSet(false, true)
                && registered.compareAndSet(true, false)) {
            HandlerList.unregisterAll(this);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (closing.get() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!browser.owns(top, player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }
        ItemStack item = top.getItem(rawSlot);
        models.replayId(item).ifPresent(replayId -> browser.openReplay(player, replayId));
        models.navigation(item).ifPresent(navigation -> {
            switch (navigation) {
                case "previous" -> browser.previous(player);
                case "next" -> browser.next(player);
                case "close" -> browser.closeFor(player.getUniqueId());
                default -> { }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (closing.get() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (browser.owns(event.getView().getTopInventory(), player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (closing.get() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        handleControl(event.getItem(), player, player.getInventory().getHeldItemSlot(), event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (closing.get()) {
            return;
        }
        Player player = event.getPlayer();
        handleControl(
                event.getItemDrop().getItemStack(),
                player,
                player.getInventory().getHeldItemSlot(),
                event::setCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (closing.get()) {
            return;
        }
        Player player = event.getPlayer();
        handleControl(
                player.getInventory().getItemInMainHand(),
                player,
                player.getInventory().getHeldItemSlot(),
                event::setCancelled);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (closing.get() || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (browser.owns(event.getInventory(), player.getUniqueId())) {
            browser.closeFor(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!closing.get()) {
            browser.closeFor(event.getPlayer().getUniqueId());
        }
    }

    private void handleControl(
            ItemStack item,
            Player player,
            int slot,
            java.util.function.Consumer<Boolean> cancel) {
        if (!environment.isViewer(player.getUniqueId())) {
            return;
        }
        models.controlAction(item).ifPresent(action -> {
            if (models.item(action).slot() != slot) {
                return;
            }
            cancel.accept(true);
            hotbar.handle(player, action);
        });
    }
}
