package dev.voldechse.replayframework.example.viewer;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps Example viewers isolated from normal server interaction while their
 * viewer environment owns the player.
 */
public final class ViewerProtectionListener implements Listener {

    /**
     * Identifies an inventory item that the Example integration uses as a
     * viewer control.
     */
    @FunctionalInterface
    public interface ControlItemMatcher {
        /**
         * Returns whether the supplied item is an Example control item.
         *
         * @param item item from the interaction event, possibly {@code null}
         * @return true when the interaction must remain available to controls
         */
        boolean isControlItem(ItemStack item);
    }

    private final ViewerSessionRegistry registry;
    private final Set<String> allowedCommandRoots;
    private final ControlItemMatcher controlItemMatcher;
    private final Consumer<Player> disconnectHandler;
    private final AtomicBoolean registered = new AtomicBoolean();

    ViewerProtectionListener(
            ViewerSessionRegistry registry,
            Set<String> allowedCommandRoots,
            ControlItemMatcher controlItemMatcher,
            Consumer<Player> disconnectHandler) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.allowedCommandRoots = Set.copyOf(Objects.requireNonNull(
                allowedCommandRoots, "allowedCommandRoots"));
        this.controlItemMatcher = Objects.requireNonNull(controlItemMatcher, "controlItemMatcher");
        this.disconnectHandler = Objects.requireNonNull(disconnectHandler, "disconnectHandler");
    }

    void register(org.bukkit.plugin.PluginManager pluginManager, org.bukkit.plugin.Plugin plugin) {
        Objects.requireNonNull(pluginManager, "pluginManager");
        Objects.requireNonNull(plugin, "plugin");
        if (registered.compareAndSet(false, true)) {
            pluginManager.registerEvents(this, plugin);
        }
    }

    void unregister() {
        if (registered.compareAndSet(true, false)) {
            HandlerList.unregisterAll(this);
        }
    }

    /**
     * Cancels damage targeting or originating from a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        if (isProtected(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Cancels entity damage involving a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (isProtected(event.getEntity()) || isProtected(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    /**
     * Cancels block breaking by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        cancelIfProtected(event.getPlayer(), event);
    }

    /**
     * Cancels block placement by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfProtected(event.getPlayer(), event);
    }

    /**
     * Cancels non-control-item interactions by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (isProtected(event.getPlayer()) && !isControlItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /**
     * Cancels entity interactions by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfProtected(event.getPlayer(), event);
    }

    /**
     * Cancels item drops by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        cancelIfProtected(event.getPlayer(), event);
    }

    /**
     * Cancels item pickup by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        cancelIfProtected(event.getEntity(), event);
    }

    /**
     * Cancels hand swaps by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        cancelIfProtected(event.getPlayer(), event);
    }

    /**
     * Cancels inventory clicks by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        cancelIfProtected(event.getWhoClicked(), event);
    }

    /**
     * Cancels inventory drags by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        cancelIfProtected(event.getWhoClicked(), event);
    }

    /**
     * Cancels inventory opens by a protected viewer.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        cancelIfProtected(event.getPlayer(), event);
    }

    /**
     * Cancels commands that are not in the configured root allowlist.
     * @param event event being inspected
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isProtected(event.getPlayer()) && !isAllowedCommand(event.getMessage())) {
            event.setCancelled(true);
        }
    }

    /**
     * Starts the common leave path after a viewer disconnects.
     * @param event disconnect event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        disconnectHandler.accept(event.getPlayer());
    }

    /**
     * Starts the common leave path after a viewer is kicked.
     * @param event kick event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        disconnectHandler.accept(event.getPlayer());
    }

    private boolean isAllowedCommand(String message) {
        if (message == null) {
            return false;
        }
        String command = message.stripLeading();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        int separator = command.indexOf(' ');
        String root = separator < 0 ? command : command.substring(0, separator);
        return !root.isBlank() && allowedCommandRoots.contains(root.toLowerCase(Locale.ROOT));
    }

    private boolean isControlItem(ItemStack item) {
        try {
            return controlItemMatcher.isControlItem(item);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isProtected(Entity entity) {
        return entity instanceof Player player && registry.isProtected(player.getUniqueId());
    }

    private void cancelIfProtected(Entity entity, org.bukkit.event.Cancellable event) {
        if (isProtected(entity)) {
            event.setCancelled(true);
        }
    }
}
