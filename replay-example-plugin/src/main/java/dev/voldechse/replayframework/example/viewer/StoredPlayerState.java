package dev.voldechse.replayframework.example.viewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;

/**
 * In-memory snapshot of the player properties changed by the Example viewer.
 *
 * <p>The snapshot owns copies of mutable Bukkit values. Capture and restore
 * are called by the environment on the Paper server thread.</p>
 */
final class StoredPlayerState {

    private final Location location;
    private final GameMode gameMode;
    private final ItemStack[] contents;
    private final ItemStack[] armorContents;
    private final ItemStack offHand;
    private final ItemStack cursor;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final float exhaustion;
    private final int level;
    private final float exp;
    private final int totalExperience;
    private final boolean allowFlight;
    private final boolean flying;
    private final boolean invulnerable;
    private final boolean invisible;
    private final boolean glowing;
    private final boolean collidable;
    private final boolean gravity;
    private final List<PotionEffect> potionEffects;
    private final int fireTicks;
    private final int freezeTicks;

    private StoredPlayerState(
            Location location,
            GameMode gameMode,
            ItemStack[] contents,
            ItemStack[] armorContents,
            ItemStack offHand,
            ItemStack cursor,
            double health,
            int foodLevel,
            float saturation,
            float exhaustion,
            int level,
            float exp,
            int totalExperience,
            boolean allowFlight,
            boolean flying,
            boolean invulnerable,
            boolean invisible,
            boolean glowing,
            boolean collidable,
            boolean gravity,
            List<PotionEffect> potionEffects,
            int fireTicks,
            int freezeTicks) {
        this.location = Objects.requireNonNull(location, "location").clone();
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
        this.contents = cloneItems(contents);
        this.armorContents = cloneItems(armorContents);
        this.offHand = cloneItem(offHand);
        this.cursor = cloneItem(cursor);
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.level = level;
        this.exp = exp;
        this.totalExperience = totalExperience;
        this.allowFlight = allowFlight;
        this.flying = flying;
        this.invulnerable = invulnerable;
        this.invisible = invisible;
        this.glowing = glowing;
        this.collidable = collidable;
        this.gravity = gravity;
        this.potionEffects = List.copyOf(potionEffects);
        this.fireTicks = fireTicks;
        this.freezeTicks = freezeTicks;
    }

    static StoredPlayerState capture(Player player) {
        Objects.requireNonNull(player, "player");
        PlayerInventory inventory = Objects.requireNonNull(player.getInventory(), "player.inventory");
        Location location = Objects.requireNonNull(player.getLocation(), "player.location");
        return new StoredPlayerState(
                location,
                player.getGameMode(),
                inventory.getContents(),
                inventory.getArmorContents(),
                inventory.getItemInOffHand(),
                player.getItemOnCursor(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getAllowFlight(),
                player.isFlying(),
                player.isInvulnerable(),
                player.isInvisible(),
                player.isGlowing(),
                player.isCollidable(),
                player.hasGravity(),
                new ArrayList<>(player.getActivePotionEffects()),
                player.getFireTicks(),
                player.getFreezeTicks());
    }

    void restore(Player player) {
        Objects.requireNonNull(player, "player");
        List<Throwable> failures = new ArrayList<>();
        restoreSection(failures, "location", () -> {
            if (!player.teleport(location.clone())) {
                throw new IllegalStateException("Original player location could not be restored");
            }
        });
        restoreSection(failures, "game mode", () -> player.setGameMode(gameMode));
        restoreSection(failures, "inventory", () -> {
            PlayerInventory inventory = Objects.requireNonNull(
                    player.getInventory(), "player.inventory");
            inventory.setContents(cloneItems(contents));
            inventory.setArmorContents(cloneItems(armorContents));
            inventory.setItemInOffHand(cloneItem(offHand));
            player.setItemOnCursor(cloneItem(cursor));
        });
        restoreSection(failures, "health and food", () -> {
            double maximumHealth = player.getMaxHealth();
            player.setHealth(Math.max(0.0, Math.min(health, maximumHealth)));
            player.setFoodLevel(foodLevel);
            player.setSaturation(saturation);
            player.setExhaustion(exhaustion);
        });
        restoreSection(failures, "experience", () -> {
            player.setTotalExperience(totalExperience);
            player.setLevel(level);
            player.setExp(exp);
        });
        restoreSection(failures, "flags", () -> {
            player.setAllowFlight(allowFlight);
            player.setFlying(allowFlight && flying);
            player.setInvulnerable(invulnerable);
            player.setInvisible(invisible);
            player.setGlowing(glowing);
            player.setCollidable(collidable);
            player.setGravity(gravity);
            player.setFireTicks(fireTicks);
            player.setFreezeTicks(freezeTicks);
        });
        restoreSection(failures, "potion effects", () -> {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
            for (PotionEffect effect : potionEffects) {
                player.addPotionEffect(effect);
            }
        });
        if (!failures.isEmpty()) {
            throw new RestoreException(failures);
        }
    }

    ItemStack[] contentsForTest() {
        return cloneItems(contents);
    }

    private static void restoreSection(
            List<Throwable> failures,
            String section,
            Runnable restore) {
        try {
            restore.run();
        } catch (Throwable failure) {
            failures.add(new IllegalStateException("Could not restore " + section, failure));
        }
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        Objects.requireNonNull(items, "items");
        return Arrays.stream(items).map(StoredPlayerState::cloneItem).toArray(ItemStack[]::new);
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    static final class RestoreException extends RuntimeException {
        private RestoreException(List<Throwable> failures) {
            super("One or more player-state sections could not be restored", failures.get(0));
            for (int index = 1; index < failures.size(); index++) {
                addSuppressed(failures.get(index));
            }
        }
    }
}
