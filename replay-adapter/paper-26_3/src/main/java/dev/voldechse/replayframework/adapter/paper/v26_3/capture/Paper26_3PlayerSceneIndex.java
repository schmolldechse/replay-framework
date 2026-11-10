package dev.voldechse.replayframework.adapter.paper.v26_3.capture;

import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Main-thread maintained player world state read by PacketEvents callbacks. */
final class Paper26_3PlayerSceneIndex implements Listener, AutoCloseable {

    private final JavaPlugin owner;
    private final Map<UUID, Key> worlds = new ConcurrentHashMap<>();
    private boolean installed;

    Paper26_3PlayerSceneIndex(JavaPlugin owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    void install() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IncompatibleAdapterException(
                    "Paper capture must be installed on the server thread");
        }
        if (installed) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
        Bukkit.getPluginManager().registerEvents(this, owner);
        installed = true;
    }

    Optional<Key> world(UUID playerId) {
        return Optional.ofNullable(worlds.get(Objects.requireNonNull(playerId, "playerId")));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        worlds.remove(event.getPlayer().getUniqueId());
    }

    private void update(Player player) {
        worlds.put(player.getUniqueId(), player.getWorld().getKey());
    }

    @Override
    public void close() {
        if (installed) {
            HandlerList.unregisterAll(this);
            installed = false;
        }
        worlds.clear();
    }
}
