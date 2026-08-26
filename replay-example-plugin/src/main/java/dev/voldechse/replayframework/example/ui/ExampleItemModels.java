package dev.voldechse.replayframework.example.ui;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.api.replay.ReplaySummary;
import dev.voldechse.replayframework.example.resourcepack.ExamplePackAsset;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Immutable Example-side UI configuration and trusted item factories.
 *
 * <p>The framework API does not expose or own these items. This class keeps
 * configuration parsing, resource-pack model validation and PDC recognition
 * in one Example-plugin boundary.</p>
 */
public final class ExampleItemModels {
    /** PDC value containing a canonical replay UUID. */
    public static final NamespacedKey BROWSER_REPLAY_ID_KEY =
            new NamespacedKey("replay_example", "browser_replay_id");
    /** PDC value containing a browser navigation action. */
    public static final NamespacedKey BROWSER_NAVIGATION_KEY =
            new NamespacedKey("replay_example", "browser_navigation");
    /** PDC value containing a hotbar control action. */
    public static final NamespacedKey CONTROL_ACTION_KEY =
            new NamespacedKey("replay_example", "control_action");

    private static final Set<String> NAVIGATION_VALUES = Set.of("previous", "close", "next");
    private static final Map<String, PlaybackSpeed> SPEED_NAMES = Map.of(
            "0.25", PlaybackSpeed.QUARTER,
            "0.5", PlaybackSpeed.HALF,
            "1", PlaybackSpeed.NORMAL,
            "2", PlaybackSpeed.DOUBLE,
            "4", PlaybackSpeed.QUADRUPLE);

    private final BrowserConfig browser;
    private final HotbarConfig hotbar;
    private final Map<HotbarAction, ItemDefinition> items;

    private ExampleItemModels(
            BrowserConfig browser,
            HotbarConfig hotbar,
            Map<HotbarAction, ItemDefinition> items) {
        this.browser = browser;
        this.hotbar = hotbar;
        this.items = Map.copyOf(items);
    }

    /** Loads and validates the bundled Gson UI configuration. */
    public static ExampleItemModels load(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        InputStream stream = plugin.getResource("example-config.json");
        if (stream == null) {
            throw new IllegalStateException(
                    "Invalid Example UI configuration: example-config.json is missing");
        }
        try (InputStream input = stream;
                Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return parse(reader, "example-config.json");
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Invalid Example UI configuration: example-config.json could not be read",
                    failure);
        }
    }

    /** Parses one configuration source; package-private for focused configuration tests. */
    static ExampleItemModels parse(String json, String sourcePath) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(sourcePath, "sourcePath");
        try {
            return parse(new java.io.StringReader(json), sourcePath);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static ExampleItemModels parse(Reader reader, String sourcePath) throws IOException {
        try {
            RawConfiguration raw = new Gson().fromJson(reader, RawConfiguration.class);
            if (raw == null) {
                throw invalid(sourcePath, "configuration is empty");
            }
            return fromRaw(raw, sourcePath);
        } catch (JsonParseException | IllegalArgumentException failure) {
            if (failure instanceof IllegalArgumentException
                    && failure.getMessage() != null
                    && failure.getMessage().contains(sourcePath)) {
                throw failure;
            }
            throw invalid(sourcePath, failure.getMessage(), failure);
        }
    }

    private static ExampleItemModels fromRaw(RawConfiguration raw, String sourcePath) {
        if (raw.browser == null) {
            throw invalid(sourcePath, "browser is missing");
        }
        if (raw.hotbar == null) {
            throw invalid(sourcePath, "hotbar is missing");
        }
        String title = required(raw.browser.title, sourcePath, "browser.title");
        if (raw.browser.size != 54) {
            throw invalid(sourcePath, "browser.size must be 54");
        }
        List<Integer> replaySlots = requiredList(
                raw.browser.replaySlots, sourcePath, "browser.replaySlots");
        if (replaySlots.size() != 45) {
            throw invalid(sourcePath, "browser.replaySlots must contain 45 slots");
        }
        validateSlots(replaySlots, 0, 53, sourcePath, "browser.replaySlots");
        Set<Integer> replaySlotSet = new HashSet<>(replaySlots);
        if (replaySlotSet.size() != replaySlots.size()) {
            throw invalid(sourcePath, "browser.replaySlots must be unique");
        }
        validateSlot(raw.browser.previousPageSlot, sourcePath, "browser.previousPageSlot");
        validateSlot(raw.browser.closeSlot, sourcePath, "browser.closeSlot");
        validateSlot(raw.browser.nextPageSlot, sourcePath, "browser.nextPageSlot");
        Set<Integer> navigationSlots = Set.of(
                raw.browser.previousPageSlot,
                raw.browser.closeSlot,
                raw.browser.nextPageSlot);
        if (navigationSlots.size() != 3 || !java.util.Collections.disjoint(replaySlotSet, navigationSlots)) {
            throw invalid(sourcePath, "browser navigation slots must be unique and separate");
        }
        if (raw.browser.loreLineLength <= 0) {
            throw invalid(sourcePath, "browser.loreLineLength must be positive");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(required(raw.browser.serverTimeZone, sourcePath, "browser.serverTimeZone"));
        } catch (RuntimeException failure) {
            throw invalid(sourcePath, "browser.serverTimeZone is invalid", failure);
        }

        if (raw.hotbar.rewindSeconds <= 0 || raw.hotbar.forwardSeconds <= 0) {
            throw invalid(sourcePath, "hotbar seek seconds must be positive");
        }
        List<RawItemDefinition> rawItems = requiredList(raw.hotbar.items, sourcePath, "hotbar.items");
        if (rawItems.size() != HotbarAction.values().length) {
            throw invalid(sourcePath, "hotbar.items must define every action exactly once");
        }
        Map<HotbarAction, ItemDefinition> items = new EnumMap<>(HotbarAction.class);
        Set<Integer> hotbarSlots = new HashSet<>();
        for (int index = 0; index < rawItems.size(); index++) {
            RawItemDefinition rawItem = rawItems.get(index);
            String path = "hotbar.items[" + index + "]";
            if (rawItem == null) {
                throw invalid(sourcePath, path + " is missing");
            }
            HotbarAction action = parseAction(rawItem.action, sourcePath, path + ".action");
            validateSlot(rawItem.slot, 0, 8, sourcePath, path + ".slot");
            if (!hotbarSlots.add(rawItem.slot)) {
                throw invalid(sourcePath, path + ".slot is duplicated");
            }
            Material material;
            try {
                material = Material.valueOf(required(rawItem.material, sourcePath, path + ".material")
                        .toUpperCase(Locale.ROOT));
            } catch (RuntimeException failure) {
                throw invalid(sourcePath, path + ".material is invalid", failure);
            }
            ExamplePackAsset model = assetForModel(rawItem.model, sourcePath, path + ".model");
            Optional<ExamplePackAsset> alternateModel = rawItem.alternateModel == null
                    ? Optional.empty()
                    : Optional.of(assetForModel(
                            rawItem.alternateModel, sourcePath, path + ".alternateModel"));
            Map<PlaybackSpeed, ExamplePackAsset> speedModels = new EnumMap<>(PlaybackSpeed.class);
            if (action == HotbarAction.SPEED_CYCLE) {
                if (rawItem.speedModels == null || rawItem.speedModels.size() != SPEED_NAMES.size()) {
                    throw invalid(sourcePath, path + ".speedModels must define all five speeds");
                }
                for (Map.Entry<String, PlaybackSpeed> entry : SPEED_NAMES.entrySet()) {
                    String modelKey = rawItem.speedModels.get(entry.getKey());
                    if (modelKey == null) {
                        throw invalid(sourcePath, path + ".speedModels is missing " + entry.getKey());
                    }
                    speedModels.put(entry.getValue(), assetForModel(
                            modelKey, sourcePath, path + ".speedModels." + entry.getKey()));
                }
            } else if (rawItem.speedModels != null && !rawItem.speedModels.isEmpty()) {
                throw invalid(sourcePath, path + ".speedModels is only valid for SPEED_CYCLE");
            }
            if (items.put(action, new ItemDefinition(
                    action, rawItem.slot, material, model, alternateModel, speedModels)) != null) {
                throw invalid(sourcePath, "hotbar action " + action + " is duplicated");
            }
        }
        for (HotbarAction action : HotbarAction.values()) {
            if (!items.containsKey(action)) {
                throw invalid(sourcePath, "hotbar action " + action + " is missing");
            }
        }

        return new ExampleItemModels(
                new BrowserConfig(
                        title,
                        raw.browser.size,
                        replaySlots,
                        raw.browser.previousPageSlot,
                        raw.browser.closeSlot,
                        raw.browser.nextPageSlot,
                        zone,
                        raw.browser.loreLineLength),
                new HotbarConfig(raw.hotbar.rewindSeconds, raw.hotbar.forwardSeconds, items.values().stream().toList()),
                items);
    }

    private static HotbarAction parseAction(String value, String sourcePath, String path) {
        try {
            return HotbarAction.valueOf(required(value, sourcePath, path));
        } catch (RuntimeException failure) {
            throw invalid(sourcePath, path + " is invalid", failure);
        }
    }

    private static ExamplePackAsset assetForModel(String value, String sourcePath, String path) {
        String model = required(value, sourcePath, path);
        ExamplePackAsset asset = itemAssetsByModelKey().get(model);
        if (asset == null) {
            throw invalid(sourcePath, path + " does not reference an Example Pack item model: " + model);
        }
        return asset;
    }

    private static Map<String, ExamplePackAsset> itemAssetsByModelKey() {
        Map<String, ExamplePackAsset> assets = new HashMap<>();
        for (ExamplePackAsset asset : ExamplePackAsset.values()) {
            asset.itemModelKey().ifPresent(key -> assets.put(key, asset));
        }
        return Map.copyOf(assets);
    }

    private static void validateSlots(
            List<Integer> slots, int minimum, int maximum, String sourcePath, String path) {
        for (int index = 0; index < slots.size(); index++) {
            Integer slot = slots.get(index);
            if (slot == null || slot < minimum || slot > maximum) {
                throw invalid(sourcePath, path + "[" + index + "] is outside the inventory");
            }
        }
    }

    private static void validateSlot(int slot, String sourcePath, String path) {
        validateSlot(slot, 0, 53, sourcePath, path);
    }

    private static void validateSlot(int slot, int minimum, int maximum, String sourcePath, String path) {
        if (slot < minimum || slot > maximum) {
            throw invalid(sourcePath, path + " is outside the inventory");
        }
    }

    private static String required(String value, String sourcePath, String path) {
        if (value == null || value.isBlank()) {
            throw invalid(sourcePath, path + " must not be blank");
        }
        return value;
    }

    private static <T> List<T> requiredList(List<T> values, String sourcePath, String path) {
        if (values == null) {
            throw invalid(sourcePath, path + " is missing");
        }
        return values;
    }

    private static IllegalArgumentException invalid(String sourcePath, String message) {
        return new IllegalArgumentException(sourcePath + ": " + message);
    }

    private static IllegalArgumentException invalid(String sourcePath, String message, Throwable cause) {
        return new IllegalArgumentException(sourcePath + ": " + message, cause);
    }

    /** Returns immutable browser configuration. */
    public BrowserConfig browser() {
        return browser;
    }

    /** Returns immutable hotbar configuration. */
    public HotbarConfig hotbar() {
        return hotbar;
    }

    /** Returns the definition for one configured hotbar action. */
    public ItemDefinition item(HotbarAction action) {
        return items.get(Objects.requireNonNull(action, "action"));
    }

    /** Returns the navigation value when a browser navigation item is valid. */
    public Optional<String> navigation(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getItemMeta() == null) {
            return Optional.empty();
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(
                BROWSER_NAVIGATION_KEY, PersistentDataType.STRING);
        return value != null && NAVIGATION_VALUES.contains(value)
                ? Optional.of(value)
                : Optional.empty();
    }

    /** Returns the canonical replay identifier on a valid browser replay item. */
    public Optional<ReplayId> replayId(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || item.getItemMeta() == null) {
            return Optional.empty();
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(
                BROWSER_REPLAY_ID_KEY, PersistentDataType.STRING);
        if (value == null) {
            return Optional.empty();
        }
        try {
            ReplayId replayId = ReplayId.parse(value);
            return replayId.toString().equals(value) ? Optional.of(replayId) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Returns the action only for a correctly modeled configured control item. */
    public Optional<HotbarAction> controlAction(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getItemMeta() == null) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        String actionValue = meta.getPersistentDataContainer().get(
                CONTROL_ACTION_KEY, PersistentDataType.STRING);
        if (actionValue == null) {
            return Optional.empty();
        }
        HotbarAction action;
        try {
            action = HotbarAction.valueOf(actionValue);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        ItemDefinition definition = items.get(action);
        if (definition == null || definition.material() != item.getType()) {
            return Optional.empty();
        }
        String modelKey = modelKey(meta);
        if (modelKey == null || !allowedModelKeys(definition).contains(modelKey)) {
            return Optional.empty();
        }
        return Optional.of(action);
    }

    /** Returns whether an item is safe to leave available to a protected viewer. */
    public boolean isControlItem(ItemStack item) {
        return controlAction(item).isPresent();
    }

    /** Creates one replay browser card with only the canonical replay PDC value. */
    public ItemStack replayItem(
            ReplaySummary summary, Component displayName, List<Component> lore) {
        Objects.requireNonNull(summary, "summary");
        return createItem(Material.PAPER, null, displayName, lore, meta ->
                meta.getPersistentDataContainer().set(
                        BROWSER_REPLAY_ID_KEY,
                        PersistentDataType.STRING,
                        summary.replayId().toString()));
    }

    /** Creates one browser navigation item. */
    public ItemStack navigationItem(
            String navigation, Material material, Component displayName, List<Component> lore) {
        if (!NAVIGATION_VALUES.contains(navigation)) {
            throw new IllegalArgumentException("Unknown browser navigation: " + navigation);
        }
        return createItem(material, null, displayName, lore, meta ->
                meta.getPersistentDataContainer().set(
                        BROWSER_NAVIGATION_KEY, PersistentDataType.STRING, navigation));
    }

    /** Creates one configured hotbar item for the current playback state. */
    public ItemStack controlItem(
            HotbarAction action,
            boolean playing,
            PlaybackSpeed speed,
            Component displayName,
            List<Component> lore) {
        ItemDefinition definition = item(action);
        ExamplePackAsset model = definition.modelFor(playing, speed);
        return createItem(definition.material(), model, displayName, lore, meta ->
                meta.getPersistentDataContainer().set(
                        CONTROL_ACTION_KEY, PersistentDataType.STRING, action.name()));
    }

    private static ItemStack createItem(
            Material material,
            ExamplePackAsset model,
            Component displayName,
            List<Component> lore,
            Consumer<ItemMeta> additionalMeta) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta(), "item.meta");
        meta.displayName(displayName);
        meta.lore(List.copyOf(lore));
        if (model != null) {
            meta.setItemModel(NamespacedKey.fromString(model.itemModelKey().orElseThrow()));
        }
        additionalMeta.accept(meta);
        item.setItemMeta(meta);
        return item;
    }

    private static String modelKey(ItemMeta meta) {
        NamespacedKey model = meta.getItemModel();
        return model == null ? null : model.asString();
    }

    private static Set<String> allowedModelKeys(ItemDefinition definition) {
        Set<String> keys = new HashSet<>();
        definition.model().itemModelKey().ifPresent(keys::add);
        definition.alternateModel().flatMap(ExamplePackAsset::itemModelKey).ifPresent(keys::add);
        definition.speedModels().values().forEach(asset -> asset.itemModelKey().ifPresent(keys::add));
        return keys;
    }

    private static final class RawConfiguration {
        private RawBrowser browser;
        private RawHotbar hotbar;
    }

    private static final class RawBrowser {
        private String title;
        private int size;
        private List<Integer> replaySlots;
        private int previousPageSlot;
        private int closeSlot;
        private int nextPageSlot;
        private String serverTimeZone;
        private int loreLineLength;
    }

    private static final class RawHotbar {
        private int rewindSeconds;
        private int forwardSeconds;
        private List<RawItemDefinition> items;
    }

    private static final class RawItemDefinition {
        private String action;
        private int slot;
        private String material;
        private String model;
        private String alternateModel;
        private Map<String, String> speedModels;
    }

    /** Browser inventory configuration. */
    public record BrowserConfig(
            String title,
            int size,
            List<Integer> replaySlots,
            int previousPageSlot,
            int closeSlot,
            int nextPageSlot,
            ZoneId serverTimeZone,
            int loreLineLength) {
        /** Copies list values and validates immutable browser bounds. */
        public BrowserConfig {
            Objects.requireNonNull(title, "title");
            replaySlots = List.copyOf(Objects.requireNonNull(replaySlots, "replaySlots"));
            Objects.requireNonNull(serverTimeZone, "serverTimeZone");
            if (size != 54 || loreLineLength <= 0) {
                throw new IllegalArgumentException("invalid browser configuration");
            }
        }
    }

    /** Hotbar configuration. */
    public record HotbarConfig(int rewindSeconds, int forwardSeconds, List<ItemDefinition> items) {
        /** Copies item definitions and validates positive seek distances. */
        public HotbarConfig {
            if (rewindSeconds <= 0 || forwardSeconds <= 0) {
                throw new IllegalArgumentException("seek seconds must be positive");
            }
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /** One immutable configured hotbar item. */
    public record ItemDefinition(
            HotbarAction action,
            int slot,
            Material material,
            ExamplePackAsset model,
            Optional<ExamplePackAsset> alternateModel,
            Map<PlaybackSpeed, ExamplePackAsset> speedModels) {
        /** Defensively copies optional model mappings. */
        public ItemDefinition {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(model, "model");
            alternateModel = Objects.requireNonNull(alternateModel, "alternateModel");
            speedModels = Map.copyOf(Objects.requireNonNull(speedModels, "speedModels"));
        }

        private ExamplePackAsset modelFor(boolean playing, PlaybackSpeed speed) {
            if (action == HotbarAction.TOGGLE_PLAY && !playing) {
                return alternateModel.orElse(model);
            }
            if (action == HotbarAction.SPEED_CYCLE) {
                return speedModels.getOrDefault(speed, model);
            }
            return model;
        }
    }

    /** Actions represented by trusted playback hotbar items. */
    public enum HotbarAction {
        /** Toggles between play and pause. */
        TOGGLE_PLAY,
        /** Seeks backwards by the configured amount. */
        REWIND,
        /** Seeks forwards by the configured amount. */
        FORWARD,
        /** Restarts the current replay. */
        RESTART,
        /** Cycles through all supported playback speeds. */
        SPEED_CYCLE,
        /** Leaves the current viewer environment. */
        LEAVE
    }
}
