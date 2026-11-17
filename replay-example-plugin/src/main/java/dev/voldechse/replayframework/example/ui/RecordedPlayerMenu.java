package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import dev.voldechse.replayframework.example.viewer.ViewerMenuHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The observer menu listing the players of a replay.
 *
 * <p>Each player is shown with its recorded world and position at the moment
 * the menu opens. Clicking a player moves the viewer to it, switching the
 * viewed world when the player is in another one. Players that are not part
 * of the scene right now, for example after logging out, are listed greyed
 * out.</p>
 *
 * <p>A left click moves the viewer to the player, a right click opens the
 * player's inventory in the {@link SubjectMenu}.</p>
 */
public final class RecordedPlayerMenu {
    private static final int SIZE = 54;

    private final JavaPlugin plugin;
    private final ExampleViewerEnvironment environment;
    private final ExampleItemModels models;
    private final SubjectMenu subjectMenu;

    public RecordedPlayerMenu(
            JavaPlugin plugin,
            ExampleViewerEnvironment environment,
            ExampleItemModels models,
            SubjectMenu subjectMenu) {
        this.subjectMenu = Objects.requireNonNull(subjectMenu, "subjectMenu");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.models = Objects.requireNonNull(models, "models");
    }

    /** Opens the menu with the players of a session at its current position. */
    void open(Player viewer, PlaybackSession session) {
        // Players in other worlds are read from those worlds' recordings.
        session.recordedPlayers().whenComplete((players, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure != null || !viewer.isOnline()) {
                return;
            }
            MenuHolder holder = new MenuHolder(viewer.getUniqueId());
            Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("Players"));
            holder.inventory = inventory;
            for (int slot = 0; slot < Math.min(players.size(), SIZE); slot++) {
                inventory.setItem(slot, item(players.get(slot)));
            }
            viewer.openInventory(inventory);
        }));
    }

    /** Whether an inventory is this menu opened for the viewer. */
    boolean owns(Inventory inventory, UUID viewerId) {
        return inventory != null
                && inventory.getHolder(false) instanceof MenuHolder holder
                && holder.viewerId.equals(viewerId);
    }

    /**
     * Handles a click on a player: a left click moves the viewer to the
     * player, a right click opens the player's inventory.
     */
    void click(Player viewer, ItemStack item, boolean rightClick) {
        UUID playerId = models.recordedPlayerId(item).orElse(null);
        PlaybackSession session = environment.playback(viewer.getUniqueId()).orElse(null);
        if (playerId == null || session == null) {
            return;
        }
        // The position of the player is read again: playback went on since
        // the menu opened.
        session.recordedPlayers().whenComplete((players, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure == null && viewer.isOnline()) {
                handle(viewer, session, players.stream()
                        .filter(player -> player.id().equals(playerId))
                        .findFirst()
                        .orElse(null), rightClick);
            }
        }));
    }

    private void handle(Player viewer, PlaybackSession session, RecordedPlayer target, boolean rightClick) {
        if (rightClick && target != null) {
            subjectMenu.open(viewer, session, target.id(), target.name());
            return;
        }
        viewer.closeInventory();
        if (target == null || !target.inScene()) {
            viewer.sendMessage(Component.text(
                    "That player is not in the replay right now.", NamedTextColor.GRAY));
            return;
        }
        environment.goTo(viewer.getUniqueId(), target).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.FINE, "Going to a recorded player failed: "
                        + failure.getClass().getSimpleName());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (viewer.isOnline()) {
                        viewer.sendMessage(Component.text(
                                "Could not go to " + target.name() + ".", NamedTextColor.RED));
                    }
                });
            }
        });
    }

    private ItemStack item(RecordedPlayer player) {
        List<Component> lore = new ArrayList<>();
        Component name;
        if (player.location().isPresent()) {
            RecordedPlayer.Location location = player.location().get();
            name = Component.text(player.name(), NamedTextColor.WHITE);
            lore.add(line("World: " + location.world().asString(), NamedTextColor.GRAY));
            lore.add(line(String.format(java.util.Locale.ROOT, "Position: %.0f, %.0f, %.0f",
                    location.x(), location.y(), location.z()), NamedTextColor.GRAY));
            lore.add(line("Click to go to this player", NamedTextColor.YELLOW));
        } else {
            name = Component.text(player.name(), NamedTextColor.DARK_GRAY);
            lore.add(line("Not in the replay right now", NamedTextColor.DARK_GRAY));
        }
        return models.recordedPlayerItem(
                player.id(), player.name(), name.decoration(TextDecoration.ITALIC, false), lore);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** Holder marking an inventory as this menu of one viewer. */
    static final class MenuHolder implements ViewerMenuHolder {
        private final UUID viewerId;
        private Inventory inventory;

        private MenuHolder(UUID viewerId) {
            this.viewerId = viewerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
