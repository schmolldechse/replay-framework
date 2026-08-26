package dev.voldechse.replayframework.example;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;
import dev.voldechse.replayframework.api.ReplayFramework;
import dev.voldechse.replayframework.api.ReplayFrameworkProvider;
import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.playback.PlaybackBufferOptions;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import dev.voldechse.replayframework.example.resourcepack.ExampleResourcePackService;
import dev.voldechse.replayframework.example.ui.ExampleItemModels;
import dev.voldechse.replayframework.example.ui.PlaybackHotbar;
import dev.voldechse.replayframework.example.ui.PlaybackStatusRenderer;
import dev.voldechse.replayframework.example.ui.ReplayBrowser;
import dev.voldechse.replayframework.example.ui.ReplayBrowserListener;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Paper entry point for the Example integration. */
public final class ReplayExamplePlugin extends JavaPlugin {
    private static final ReplayMetadataKey<String> CATEGORY_KEY = createCategoryKey();
    private static final long FRAMEWORK_WAIT_TICKS = 20L * 30L;

    private final AtomicReference<ReplayCommand.Context> commandContext = new AtomicReference<>();
    private final AtomicReference<Optional<ReplayBrowser>> browserReference =
            new AtomicReference<>(Optional.empty());
    private final AtomicBoolean closing = new AtomicBoolean();
    private ReplayCommand replayCommand;
    private BukkitTask initializationTask;
    private ExampleResourcePackService resourcePackService;
    private ExampleViewerEnvironment viewerEnvironment;
    private PlaybackHotbar playbackHotbar;
    private PlaybackStatusRenderer statusRenderer;
    private ReplayBrowser replayBrowser;
    private ReplayBrowserListener browserListener;
    private long frameworkWaitTicks;

    @Override
    public void onEnable() {
        replayCommand = new ReplayCommand(this, commandContext::get, browserReference::get);
        getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                event -> event.registrar().register(replayCommand.buildTree(), "replay"));
        initializationTask = getServer().getScheduler().runTaskTimer(
                this,
                this::initializeServicesWhenAvailable,
                1L,
                1L);
    }

    @Override
    public void onDisable() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        BukkitTask task = initializationTask;
        if (task != null) {
            task.cancel();
            initializationTask = null;
        }
        commandContext.set(null);
        browserReference.set(Optional.empty());
        ReplayBrowserListener listener = browserListener;
        browserListener = null;
        if (listener != null) {
            listener.close();
        }
        PlaybackStatusRenderer renderer = statusRenderer;
        statusRenderer = null;
        if (renderer != null) {
            renderer.close();
        }
        ReplayBrowser browser = replayBrowser;
        replayBrowser = null;
        if (browser != null) {
            browser.close();
        }
        PlaybackHotbar hotbar = playbackHotbar;
        playbackHotbar = null;
        if (hotbar != null) {
            hotbar.close();
        }
        if (replayCommand != null) {
            replayCommand.close();
        }
        ExampleViewerEnvironment environment = viewerEnvironment;
        viewerEnvironment = null;
        if (environment != null) {
            environment.close().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    getLogger().warning("Example viewer shutdown failed");
                }
            });
        }
        ExampleResourcePackService packService = resourcePackService;
        resourcePackService = null;
        if (packService != null) {
            packService.close();
        }
    }

    private void initializeServicesWhenAvailable() {
        if (closing.get() || commandContext.get() != null) {
            return;
        }
        ReplayFramework framework = findFramework();
        if (framework == null) {
            if (++frameworkWaitTicks >= FRAMEWORK_WAIT_TICKS) {
                getLogger().severe("Replay Framework service was not published during enable");
                getServer().getPluginManager().disablePlugin(this);
            }
            return;
        }
        frameworkWaitTicks = 0L;

        Map<String, ReplayMetadataKey<?>> metadataKeys = registerExampleMetadata(framework);
        Optional<ExampleViewerEnvironment> environment = Optional.empty();
        try {
            ExampleItemModels models = ExampleItemModels.load(this);
            ExampleViewerEnvironment createdEnvironment = createViewerEnvironment(framework, models);
            PlaybackHotbar hotbar = new PlaybackHotbar(this, createdEnvironment, models);
            playbackHotbar = hotbar;
            PlaybackStatusRenderer renderer = new PlaybackStatusRenderer(
                    this, framework.events(), createdEnvironment, hotbar);
            statusRenderer = renderer;
            ReplayBrowser browser = new ReplayBrowser(
                    this,
                    framework.replays(),
                    createdEnvironment,
                    models,
                    hotbar,
                    renderer);
            replayBrowser = browser;
            ReplayBrowserListener listener = new ReplayBrowserListener(
                    this, browser, hotbar, createdEnvironment, models);
            browserListener = listener;
            listener.register();

            environment = Optional.of(createdEnvironment);
            browserReference.set(Optional.of(browser));
        } catch (RuntimeException failure) {
            closeUiAfterSetupFailure();
            environment = Optional.empty();
            getLogger().warning(
                    "Example viewer commands remain unavailable: "
                            + failure.getClass().getSimpleName());
        }
        commandContext.set(new ReplayCommand.Context(framework, environment, metadataKeys));
        BukkitTask task = initializationTask;
        if (task != null) {
            task.cancel();
            initializationTask = null;
        }
        if (environment.isPresent()) {
            getLogger().info("Replay Example services are ready");
        }
    }

    private ReplayFramework findFramework() {
        ReplayFramework framework = getServer().getServicesManager().load(ReplayFramework.class);
        if (framework != null) {
            return framework;
        }
        try {
            return ReplayFrameworkProvider.get();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private Map<String, ReplayMetadataKey<?>> registerExampleMetadata(
            ReplayFramework framework) {
        try {
            framework.metadata().register(CATEGORY_KEY);
            return Map.of(CATEGORY_KEY.key().asString().toLowerCase(Locale.ROOT), CATEGORY_KEY);
        } catch (IllegalArgumentException failure) {
            getLogger().warning("Example metadata key registration was rejected");
            return Map.of();
        }
    }

    private ExampleViewerEnvironment createViewerEnvironment(
            ReplayFramework framework,
            ExampleItemModels models) {
        FileConfiguration configuration = getConfig();
        ExampleResourcePackService packService = null;
        try {
            URI uri = URI.create(requiredString(configuration, "resource-pack.url"));
            String sha1 = requiredString(configuration, "resource-pack.sha1")
                    .toLowerCase(Locale.ROOT);
            Duration timeout = Duration.ofMillis(
                    configuration.getLong("resource-pack.handshake-timeout-ms", 15_000L));
            Component prompt = Component.text(
                    configuration.getString("resource-pack.prompt", "Replay Framework Example"));
            packService = new ExampleResourcePackService(
                    this,
                    new ExampleResourcePackService.Configuration(uri, sha1, timeout, prompt));

            Location location = viewerLocation(configuration);
            ExampleViewerEnvironment environment = new ExampleViewerEnvironment(
                    this,
                    framework.playbacks(),
                    packService,
                    new ExampleViewerEnvironment.Configuration(
                            location,
                            GameMode.ADVENTURE,
                            PlaybackBufferOptions.builder().build(),
                            Set.of("replay"),
                            models::isControlItem));
            resourcePackService = packService;
            viewerEnvironment = environment;
            return environment;
        } catch (RuntimeException failure) {
            if (packService != null) {
                packService.close();
            }
            throw failure;
        }
    }

    private void closeUiAfterSetupFailure() {
        browserReference.set(Optional.empty());
        if (browserListener != null) {
            browserListener.close();
            browserListener = null;
        }
        if (statusRenderer != null) {
            statusRenderer.close();
            statusRenderer = null;
        }
        if (replayBrowser != null) {
            replayBrowser.close();
            replayBrowser = null;
        }
        if (playbackHotbar != null) {
            playbackHotbar.close();
            playbackHotbar = null;
        }
        if (viewerEnvironment != null) {
            viewerEnvironment.close();
            viewerEnvironment = null;
        }
        if (resourcePackService != null) {
            resourcePackService.close();
            resourcePackService = null;
        }
    }

    private static String requiredString(FileConfiguration configuration, String path) {
        String value = configuration.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing Example configuration: " + path);
        }
        return value;
    }

    private static Location viewerLocation(FileConfiguration configuration) {
        World world = Bukkit.getWorld(requiredString(configuration, "viewer.world"));
        if (world == null) {
            throw new IllegalStateException("Configured Example viewer world is not loaded");
        }
        if (!configuration.isSet("viewer.x")
                || !configuration.isSet("viewer.y")
                || !configuration.isSet("viewer.z")) {
            throw new IllegalStateException("Example viewer coordinates are incomplete");
        }
        return new Location(
                world,
                configuration.getDouble("viewer.x"),
                configuration.getDouble("viewer.y"),
                configuration.getDouble("viewer.z"),
                (float) configuration.getDouble("viewer.yaw", 0.0D),
                (float) configuration.getDouble("viewer.pitch", 0.0D));
    }

    private static ReplayMetadataKey<String> createCategoryKey() {
        TypeAdapter<String> codec = new TypeAdapter<>() {
            @Override
            public void write(JsonWriter out, String value) throws IOException {
                if (value == null) {
                    out.nullValue();
                } else {
                    out.value(value);
                }
            }

            @Override
            public String read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                if (in.peek() != JsonToken.STRING) {
                    throw new IOException("Example category must be a JSON string");
                }
                return in.nextString();
            }
        };
        return ReplayMetadataKey.of(
                Key.key("replay_example", "category"),
                TypeToken.get(String.class),
                codec,
                QueryCapabilities.of(QueryCapabilities.Operation.EQUALITY));
    }
}
