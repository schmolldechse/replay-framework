package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.event.ReplayEventPublisher;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import dev.voldechse.replayframework.example.viewer.ViewerMenuHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

/**
 * Shows what a replay knows about a player or an entity: its equipment, the
 * sections of its inventory and its vitals, at the current playback position.
 *
 * <p>It opens when a viewer right-clicks an entity or a container block of
 * the replay, such as a chest, or a player in the players menu. While it is open it follows the playback every other
 * tick and changes only the slots whose item changed, so the view does not
 * flicker.</p>
 *
 * <p>Row 1 holds the equipment and an info item. A state with a hotbar and an
 * inventory is laid out like a player inventory: the inventory in rows 2 to
 * 4, the hotbar in row 5 and a marker under the selected slot in row 6. Any
 * other state shows its sections one after another from row 2.</p>
 */
public final class SubjectMenu implements AutoCloseable {
    private static final int SIZE = 54;
    private static final long REFRESH_TICKS = 2L;
    private static final List<EquipmentSlot> EQUIPMENT_ROW = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.BODY, EquipmentSlot.OFF_HAND, EquipmentSlot.HAND);
    private static final int INFO_SLOT = 8;

    private final JavaPlugin plugin;
    private final ExampleViewerEnvironment environment;
    private final ReplayEventPublisher.Subscription subscription;

    public SubjectMenu(JavaPlugin plugin, ExampleViewerEnvironment environment, ReplayEventPublisher events) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.subscription = Objects.requireNonNull(events, "events").subscribe(this::handleEvent);
    }

    /** Opens the menu of a replay entity for a viewer. */
    void open(Player viewer, PlaybackSession session, RecordedEntity entity) {
        Optional<String> customName = entity.customName()
                .map(name -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(name));
        boolean player = entity.subject().isPresent()
                && entity.subject().get().kind() == RecordedSubject.Kind.PLAYER;
        if (customName.isPresent() || !player) {
            open(viewer, session, entity.id(), entity.subject(),
                    customName.orElseGet(() -> typeName(entity.type().value())));
            return;
        }
        session.recordedPlayers().whenComplete((players, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            String name = failure != null ? entity.id().toString() : players.stream()
                    .filter(candidate -> candidate.id().equals(entity.id()))
                    .map(dev.voldechse.replayframework.api.playback.RecordedPlayer::name)
                    .findFirst()
                    .orElse(entity.id().toString());
            open(viewer, session, entity.id(), entity.subject(), name);
        }));
    }

    /** Opens the menu of a recorded player, also one in another world. */
    void open(Player viewer, PlaybackSession session, UUID playerId, String name) {
        open(viewer, session, playerId, Optional.of(RecordedSubject.player(playerId)), name);
    }

    @Override
    public void close() {
        subscription.close();
    }

    /** Whether an inventory is this menu. */
    boolean owns(Inventory inventory, UUID viewerId) {
        return inventory != null
                && inventory.getHolder(false) instanceof MenuHolder holder
                && holder.viewerId.equals(viewerId);
    }

    /** Stops following the playback when the viewer closes the menu. */
    void closed(Inventory inventory) {
        if (inventory != null && inventory.getHolder(false) instanceof MenuHolder holder && holder.refresh != null) {
            holder.refresh.cancel();
        }
    }

    /** Opens the menu of a container block of the replay, such as a chest. */
    void open(Player viewer, PlaybackSession session, Key world, BlockPosition position, Key block) {
        MenuHolder holder = new MenuHolder(viewer.getUniqueId(), session, null, Optional.empty());
        holder.block = new BlockTarget(world, position);
        show(viewer, holder, typeName(block.value()));
    }

    private void open(
            Player viewer, PlaybackSession session, UUID entityId, Optional<RecordedSubject> subject, String title) {
        show(viewer, new MenuHolder(viewer.getUniqueId(), session, entityId, subject), title);
    }

    private void show(Player viewer, MenuHolder holder, String title) {
        holder.inventory = Bukkit.createInventory(holder, SIZE, Component.text(title));
        viewer.openInventory(holder.inventory);
        refresh(holder);
        holder.refresh = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline() || viewer.getOpenInventory().getTopInventory() != holder.inventory) {
                holder.refresh.cancel();
                return;
            }
            refresh(holder);
        }, REFRESH_TICKS, REFRESH_TICKS);
    }

    private void refresh(MenuHolder holder) {
        if (holder.querying) {
            // The previous query has not answered yet; it shows the newer state anyway.
            return;
        }
        Optional<RecordedEntity> entity = holder.entityId == null
                ? Optional.empty()
                : holder.session.entities().stream()
                        .filter(candidate -> candidate.id().equals(holder.entityId))
                        .findFirst();
        java.util.concurrent.CompletionStage<Optional<SubjectState>> query;
        if (holder.block != null) {
            query = holder.session.container(holder.block.world(), holder.block.position());
        } else if (holder.subject.isPresent()) {
            query = holder.session.state(holder.subject.get());
        } else {
            render(holder, entity, Optional.empty());
            return;
        }
        holder.querying = true;
        query.whenComplete((state, failure) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    holder.querying = false;
                    render(holder, entity, failure == null ? state : Optional.empty());
                }));
    }

    private void render(MenuHolder holder, Optional<RecordedEntity> entity, Optional<SubjectState> state) {
        ItemStack[] wanted = layout(entity, state);
        Inventory inventory = holder.inventory;
        for (int slot = 0; slot < SIZE; slot++) {
            ItemStack shown = inventory.getItem(slot);
            boolean empty = wanted[slot] == null || wanted[slot].getType().isAir();
            boolean shownEmpty = shown == null || shown.getType().isAir();
            if (empty ? !shownEmpty : !wanted[slot].equals(shown)) {
                inventory.setItem(slot, empty ? null : wanted[slot]);
            }
        }
    }

    /** What the menu shows for a state: equipment and info in row 1, the sections below. */
    private static ItemStack[] layout(Optional<RecordedEntity> entity, Optional<SubjectState> state) {
        ItemStack[] slots = new ItemStack[SIZE];
        Map<EquipmentSlot, ItemStack> equipment = state.map(SubjectState::equipment)
                .filter(worn -> !worn.isEmpty())
                .or(() -> entity.map(RecordedEntity::equipment))
                .orElse(Map.of());
        for (int slot = 0; slot < EQUIPMENT_ROW.size(); slot++) {
            slots[slot] = equipment.get(EQUIPMENT_ROW.get(slot));
        }
        slots[INFO_SLOT] = info(entity, state);

        if (state.isEmpty()) {
            return slots;
        }
        Optional<SubjectState.Section> hotbar = state.get().section(SubjectState.HOTBAR);
        Optional<SubjectState.Section> main = state.get().section(SubjectState.INVENTORY);
        if (hotbar.isPresent() && main.isPresent()) {
            place(slots, 9, main.get().items(), 27);
            place(slots, 36, hotbar.get().items(), 9);
            hotbar.get().selected().ifPresent(selected -> slots[45 + selected] = marker("Selected slot"));
            return slots;
        }
        int next = 9;
        for (SubjectState.Section section : state.get().sections()) {
            next = place(slots, next, section.items(), SIZE - next);
        }
        return slots;
    }

    private static int place(ItemStack[] slots, int start, List<ItemStack> items, int room) {
        int count = Math.min(items.size(), room);
        for (int index = 0; index < count; index++) {
            slots[start + index] = items.get(index);
        }
        return start + count;
    }

    private static ItemStack info(Optional<RecordedEntity> entity, Optional<SubjectState> state) {
        List<Component> lore = new ArrayList<>();
        if (entity.isEmpty() && state.isEmpty()) {
            lore.add(line("Not in the replay right now", NamedTextColor.DARK_GRAY));
        }
        entity.ifPresent(seen -> {
            lore.add(line("Type: " + typeName(seen.type().value()), NamedTextColor.GRAY));
            if (seen.health().isPresent()) {
                String max = seen.maxHealth().isPresent()
                        ? " / " + format(seen.maxHealth().getAsDouble())
                        : "";
                lore.add(line("Health: " + format(seen.health().getAsDouble()) + max, NamedTextColor.RED));
            }
            for (PotionEffect effect : seen.effects()) {
                lore.add(line(typeName(effect.getType().getKey().value()) + " " + (effect.getAmplifier() + 1)
                        + " (" + effect.getDuration() / 20 + "s)", NamedTextColor.LIGHT_PURPLE));
            }
        });
        state.ifPresent(known -> {
            known.vitals().ifPresent(vitals -> {
                lore.add(line("Health: " + format(vitals.health()), NamedTextColor.RED));
                lore.add(line("Food: " + vitals.food() + " (saturation " + format(vitals.saturation()) + ")",
                        NamedTextColor.GOLD));
            });
            known.experience().ifPresent(xp -> lore.add(line(
                    "Level " + xp.level() + " (" + Math.round(xp.progress() * 100) + "%)", NamedTextColor.GREEN)));
        });
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line("Details", NamedTextColor.WHITE));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack marker(String name) {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line(name, NamedTextColor.GREEN));
        item.setItemMeta(meta);
        return item;
    }

    private void handleEvent(ReplayEventPublisher.ReplayEvent event) {
        if (event instanceof ReplayEventPublisher.PlaybackBlockInteracted clicked) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player viewer = Bukkit.getPlayer(clicked.viewerId());
                if (viewer == null || !viewer.isOnline()) {
                    return;
                }
                environment.playback(viewer.getUniqueId())
                        .filter(session -> session.id().equals(clicked.sessionId()))
                        .ifPresent(session -> open(viewer, session, clicked.world(), clicked.position(), clicked.block()));
            });
            return;
        }
        if (!(event instanceof ReplayEventPublisher.PlaybackEntityInteracted interacted) || interacted.attack()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player viewer = Bukkit.getPlayer(interacted.viewerId());
            if (viewer == null || !viewer.isOnline()) {
                return;
            }
            environment.playback(viewer.getUniqueId())
                    .filter(session -> session.id().equals(interacted.sessionId()))
                    .ifPresent(session -> open(viewer, session, interacted.entity()));
        });
    }

    private static String typeName(String key) {
        String spaced = key.replace('_', ' ');
        return spaced.isEmpty() ? spaced : spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** A container block of the replay. */
    private record BlockTarget(Key world, BlockPosition position) {
    }

    /** Holder marking an inventory as this menu of one viewer and one subject. */
    static final class MenuHolder implements ViewerMenuHolder {
        private final UUID viewerId;
        private final PlaybackSession session;
        private final UUID entityId;
        private final Optional<RecordedSubject> subject;
        private Inventory inventory;
        private BlockTarget block;
        private BukkitTask refresh;
        private boolean querying;

        private MenuHolder(
                UUID viewerId, PlaybackSession session, UUID entityId, Optional<RecordedSubject> subject) {
            this.viewerId = viewerId;
            this.session = session;
            this.entityId = entityId;
            this.subject = subject;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
