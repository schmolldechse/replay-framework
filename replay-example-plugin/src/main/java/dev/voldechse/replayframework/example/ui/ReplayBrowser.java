package dev.voldechse.replayframework.example.ui;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.api.replay.ReplayService;
import dev.voldechse.replayframework.api.replay.ReplaySummary;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns the per-player, paginated replay browser inventory. */
public final class ReplayBrowser implements AutoCloseable {
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                    .withLocale(Locale.GERMANY);

    private final JavaPlugin plugin;
    private final ReplayService replayService;
    private final ExampleViewerEnvironment environment;
    private final ExampleItemModels models;
    private final PlaybackHotbar hotbar;
    private final PlaybackStatusRenderer renderer;
    private final Map<UUID, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ReplayBrowser(
            JavaPlugin plugin,
            ReplayService replayService,
            ExampleViewerEnvironment environment,
            ExampleItemModels models,
            PlaybackHotbar hotbar,
            PlaybackStatusRenderer renderer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.replayService = Objects.requireNonNull(replayService, "replayService");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.models = Objects.requireNonNull(models, "models");
        this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Opens or refreshes the first browser page for one online player. */
    public void open(Player player) {
        Objects.requireNonNull(player, "player");
        onMain(() -> {
            if (closed.get() || !player.isOnline()) {
                return;
            }
            UUID viewerId = player.getUniqueId();
            if (environment.isViewer(viewerId)) {
                player.sendMessage(Component.text(
                        "Leave the active replay before opening the replay browser.",
                        NamedTextColor.RED));
                return;
            }
            BrowserSession previous = sessions.remove(viewerId);
            if (previous != null) {
                invalidate(previous, false);
            }
            BrowserSession session = new BrowserSession(viewerId);
            sessions.put(viewerId, session);
            requestPage(player, session, Optional.empty());
        });
    }

    /** Opens a replay selected from the current page after the environment accepts it. */
    void openReplay(Player player, ReplayId replayId) {
        if (player == null || replayId == null) {
            return;
        }
        onMain(() -> {
            BrowserSession session = sessions.get(player.getUniqueId());
            if (!isCurrent(session, player) || session.pendingReplay.isPresent()) {
                return;
            }
            if (session.page == null || session.page.items().stream().noneMatch(summary ->
                    summary.replayId().equals(replayId)
                            && summary.status() == RecordingStatus.AVAILABLE)) {
                return;
            }
            session.pendingReplay = Optional.of(replayId);
            // Closing the inventory fires InventoryCloseEvent synchronously on
            // the server thread. Remove the browser first so that the close
            // event cannot cancel the viewer reservation while the viewer
            // environment is teleporting the player.
            sessions.remove(session.viewerId, session);
            session.closed = true;
            player.closeInventory();
            try {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        startBrowserOpen(player, session, replayId));
            } catch (Throwable failure) {
                completeOpen(player, session, replayId, null, failure);
            }
        });
    }

    /** Opens a replay directly from a command and installs the viewer UI. */
    public CompletionStage<PlaybackSession> openDirect(Player player, ReplayId replayId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(replayId, "replayId");
        CompletableFuture<PlaybackSession> result = new CompletableFuture<>();
        onMain(() -> {
            if (closed.get() || !player.isOnline()) {
                result.completeExceptionally(new IllegalStateException("replay browser is closed"));
                return;
            }
            if (environment.isViewer(player.getUniqueId())) {
                result.completeExceptionally(new IllegalStateException(
                        "player already has an active replay viewer"));
                return;
            }
            BrowserSession previous = sessions.remove(player.getUniqueId());
            if (previous != null) {
                invalidate(previous, false);
            }
            player.closeInventory();
            try {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        startDirectOpen(player, replayId, result));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Moves one browser to the previous cursor in its own history. */
    void previous(Player player) {
        navigate(player, true);
    }

    /** Moves one browser to the next opaque cursor. */
    void next(Player player) {
        navigate(player, false);
    }

    /** Returns whether an inventory belongs to this browser instance. */
    boolean owns(Inventory inventory) {
        return inventory != null
                && inventory.getHolder(false) instanceof BrowserHolder holder
                && holder.owner == this;
    }

    /** Returns whether an inventory belongs to the specified viewer. */
    boolean owns(Inventory inventory, UUID viewerId) {
        return owns(inventory)
                && inventory.getHolder(false) instanceof BrowserHolder holder
                && holder.viewerId.equals(viewerId);
    }

    /** Removes browser state for one viewer and cancels any pending open reservation. */
    public void closeFor(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        onMain(() -> {
            BrowserSession session = sessions.remove(viewerId);
            if (session != null) {
                invalidate(session, true);
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        onMain(() -> {
            for (BrowserSession session : List.copyOf(sessions.values())) {
                sessions.remove(session.viewerId, session);
                invalidate(session, true);
            }
        });
    }

    private void navigate(Player player, boolean previous) {
        if (player == null) {
            return;
        }
        onMain(() -> {
            BrowserSession session = sessions.get(player.getUniqueId());
            if (!isCurrent(session, player)
                    || session.pendingReplay.isPresent()
                    || session.page == null) {
                return;
            }
            if (previous) {
                if (session.previousCursors.isEmpty()) {
                    return;
                }
                Optional<String> cursor = session.previousCursors.removeLast();
                requestPage(player, session, cursor);
            } else {
                if (session.page.nextCursor().isEmpty()) {
                    return;
                }
                session.previousCursors.addLast(session.cursor);
                requestPage(player, session, session.page.nextCursor());
            }
        });
    }

    private void requestPage(
            Player player,
            BrowserSession session,
            Optional<String> cursor) {
        if (!isCurrent(session, player)) {
            return;
        }
        long generation = ++session.generation;
        ReplayQuery.Builder queryBuilder = ReplayQuery.builder()
                .status(RecordingStatus.AVAILABLE)
                .orderBy(ReplayQuery.SortField.CREATED_AT, ReplayQuery.SortDirection.DESCENDING)
                .limit(models.browser().replaySlots().size());
        cursor.ifPresent(queryBuilder::cursor);
        ReplayQuery query = queryBuilder.build();
        CompletionStage<ReplayPage<ReplaySummary>> stage;
        try {
            stage = replayService.querySummaries(query);
        } catch (Throwable failure) {
            completePage(player, session, generation, failure, null, cursor);
            return;
        }
        if (stage == null) {
            completePage(player, session, generation,
                    new NullPointerException("replay service returned no stage"), null, cursor);
            return;
        }
        stage.whenComplete((page, failure) -> onMain(() ->
                completePage(player, session, generation, failure, page, cursor)));
    }

    private void completePage(
            Player player,
            BrowserSession session,
            long generation,
            Throwable failure,
            ReplayPage<ReplaySummary> page,
            Optional<String> cursor) {
        if (!isCurrent(session, player) || session.generation != generation) {
            return;
        }
        if (failure != null) {
            player.sendMessage(Component.text(
                    "Replay list could not be loaded. Please try again.", NamedTextColor.RED));
            return;
        }
        if (page == null) {
            player.sendMessage(Component.text(
                    "Replay list returned no page.", NamedTextColor.RED));
            return;
        }
        session.cursor = cursor;
        session.page = page;
        render(player, session);
    }

    private void render(Player player, BrowserSession session) {
        Inventory inventory = session.holder.inventory;
        inventory.clear();
        List<Integer> replaySlots = models.browser().replaySlots();
        for (int index = 0; index < replaySlots.size(); index++) {
            if (session.page.items().size() <= index) {
                inventory.setItem(replaySlots.get(index), emptyFiller());
                continue;
            }
            ReplaySummary summary = session.page.items().get(index);
            inventory.setItem(replaySlots.get(index), replayItem(summary));
        }
        inventory.setItem(
                models.browser().previousPageSlot(),
                session.previousCursors.isEmpty()
                        ? emptyFiller()
                        : models.navigationItem(
                                "previous", Material.ARROW, Component.text("Previous page"), List.of()));
        inventory.setItem(
                models.browser().closeSlot(),
                models.navigationItem(
                        "close", Material.BARRIER, Component.text("Close"), List.of()));
        inventory.setItem(
                models.browser().nextPageSlot(),
                session.page.hasNext()
                        ? models.navigationItem(
                                "next", Material.ARROW, Component.text("Next page"), List.of())
                        : emptyFiller());
        if (session.page.items().isEmpty()) {
            inventory.setItem(
                    replaySlots.getFirst(),
                    models.navigationItem(
                            "close", Material.BARRIER,
                            Component.text("No available replays", NamedTextColor.GRAY), List.of()));
        }
        if (!player.getOpenInventory().getTopInventory().equals(inventory)) {
            player.openInventory(inventory);
        }
    }

    private ItemStack replayItem(ReplaySummary summary) {
        List<Component> lore = new ArrayList<>();
        appendWrapped(lore, summary.title(), models.browser().loreLineLength());
        appendWrapped(lore, summary.description(), models.browser().loreLineLength());
        lore.add(Component.text("Duration: " + formatDuration(summary.duration())));
        lore.add(Component.text("Created: " + CREATED_AT_FORMAT.format(
                ZonedDateTime.ofInstant(summary.createdAt(), models.browser().serverTimeZone()))));
        lore.add(Component.text("Adapter: " + summary.adapterId()));
        lore.add(Component.text("Click to open", NamedTextColor.GRAY));
        return models.replayItem(summary, Component.text(summary.title()), lore);
    }

    private static void appendWrapped(List<Component> lore, String text, int lineLength) {
        String value = text == null || text.isBlank() ? " " : text;
        String remaining = value;
        while (remaining.length() > lineLength) {
            int split = remaining.lastIndexOf(' ', lineLength);
            if (split <= 0) {
                split = lineLength;
            }
            lore.add(Component.text(remaining.substring(0, split)));
            remaining = remaining.substring(split).stripLeading();
        }
        lore.add(Component.text(remaining));
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }

    private void completeOpen(
            Player player,
            BrowserSession session,
            ReplayId replayId,
            Object value,
            Throwable failure) {
        if (!session.pendingReplay.filter(replayId::equals).isPresent()) {
            return;
        }
        session.pendingReplay = Optional.empty();
        if (failure != null) {
            if (player.isOnline()) {
                player.sendMessage(Component.text(
                        "Replay could not be opened. Please try again.", NamedTextColor.RED));
            }
            return;
        }
        if (!(value instanceof dev.voldechse.replayframework.api.playback.PlaybackSession playback)) {
            if (player.isOnline()) {
                player.sendMessage(Component.text(
                        "Replay could not be opened. Please try again.", NamedTextColor.RED));
            }
            if (environment.isViewer(session.viewerId)) {
                environment.leave(session.viewerId);
            }
            return;
        }
        if (!player.isOnline() || sessions.containsKey(session.viewerId)) {
            environment.leave(session.viewerId);
            return;
        }
        try {
            installPlaybackUi(player, playback);
        } catch (Throwable installFailure) {
            environment.leave(session.viewerId);
            player.sendMessage(Component.text(
                    "Replay could not be opened. Please try again.", NamedTextColor.RED));
        }
    }

    private void startBrowserOpen(Player player, BrowserSession session, ReplayId replayId) {
        if (closed.get() || !player.isOnline()) {
            completeOpen(player, session, replayId, null,
                    new IllegalStateException("replay browser is closed"));
            return;
        }
        CompletionStage<?> openStage;
        try {
            openStage = environment.open(player, replayId);
        } catch (Throwable failure) {
            completeOpen(player, session, replayId, null, failure);
            return;
        }
        if (openStage == null) {
            completeOpen(player, session, replayId, null,
                    new NullPointerException("viewer environment returned no stage"));
            return;
        }
        openStage.whenComplete((value, failure) -> onMain(() ->
                completeOpen(player, session, replayId, value, failure)));
    }

    private void startDirectOpen(
            Player player,
            ReplayId replayId,
            CompletableFuture<PlaybackSession> result) {
        if (closed.get() || !player.isOnline()) {
            result.completeExceptionally(new IllegalStateException("replay browser is closed"));
            return;
        }
        CompletionStage<PlaybackSession> openStage;
        try {
            openStage = environment.open(player, replayId);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            return;
        }
        if (openStage == null) {
            result.completeExceptionally(new NullPointerException(
                    "viewer environment returned no stage"));
            return;
        }
        openStage.whenComplete((playback, failure) -> onMain(() -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            if (playback == null || !player.isOnline()) {
                if (environment.isViewer(player.getUniqueId())) {
                    environment.leave(player.getUniqueId());
                }
                result.completeExceptionally(new IllegalStateException(
                        "viewer environment returned no playback session"));
                return;
            }
            try {
                installPlaybackUi(player, playback);
                result.complete(playback);
            } catch (Throwable installFailure) {
                environment.leave(player.getUniqueId());
                result.completeExceptionally(installFailure);
            }
        }));
    }

    private void installPlaybackUi(Player player, PlaybackSession playback) {
        hotbar.install(player, playback);
        renderer.renderInitial(player, playback.snapshot());
    }

    private void invalidate(BrowserSession session, boolean leavePending) {
        session.closed = true;
        session.generation++;
        Player player = Bukkit.getPlayer(session.viewerId);
        if (player != null && owns(player.getOpenInventory().getTopInventory(), session.viewerId)) {
            player.closeInventory();
        }
        if (leavePending && session.pendingReplay.isPresent()) {
            session.pendingReplay = Optional.empty();
            environment.leave(session.viewerId);
        }
    }

    private boolean isCurrent(BrowserSession session, Player player) {
        return !closed.get()
                && session != null
                && !session.closed
                && session.viewerId.equals(player.getUniqueId())
                && player.isOnline()
                && sessions.get(session.viewerId) == session;
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else if (plugin.isEnabled()) {
            try {
                plugin.getServer().getScheduler().runTask(plugin, action);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.FINE, "Replay browser task rejected during shutdown");
            }
        }
    }

    private static ItemStack emptyFiller() {
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            filler.setItemMeta(meta);
        }
        return filler;
    }

    private final class BrowserSession {
        private final UUID viewerId;
        private final BrowserHolder holder;
        private final Deque<Optional<String>> previousCursors = new ArrayDeque<>();
        private Optional<String> cursor = Optional.empty();
        private ReplayPage<ReplaySummary> page;
        private long generation;
        private Optional<ReplayId> pendingReplay = Optional.empty();
        private boolean closed;

        private BrowserSession(UUID viewerId) {
            this.viewerId = viewerId;
            this.holder = new BrowserHolder(viewerId);
            this.holder.inventory = Bukkit.createInventory(
                    holder, models.browser().size(), Component.text(models.browser().title()));
        }
    }

    /** Inventory holder carrying the owning browser identity and viewer UUID. */
    final class BrowserHolder implements InventoryHolder {
        private final ReplayBrowser owner;
        private final UUID viewerId;
        private Inventory inventory;

        private BrowserHolder(UUID viewerId) {
            this.owner = ReplayBrowser.this;
            this.viewerId = viewerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
