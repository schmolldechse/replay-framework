package dev.voldechse.replayframework.example.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.voldechse.replayframework.api.ReplayFramework;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.example.command.subcommand.ReplayDeleteCommand;
import dev.voldechse.replayframework.example.command.subcommand.ReplayInfoCommand;
import dev.voldechse.replayframework.example.command.subcommand.ReplayListCommand;
import dev.voldechse.replayframework.example.command.subcommand.ReplayMetadataCommands;
import dev.voldechse.replayframework.example.command.subcommand.ReplayPlaybackCommands;
import dev.voldechse.replayframework.example.command.subcommand.ReplayRecordingCommands;
import dev.voldechse.replayframework.example.ui.ReplayBrowser;
import dev.voldechse.replayframework.example.command.argument.ReplaySuggestions;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

/**
 * Composes the Example command tree and provides the shared command boundary.
 */
public final class ReplayCommand implements AutoCloseable {
    public static final String ROOT = "replay";

    public static final String LIST_PERMISSION = "replay.command.list";
    public static final String INFO_PERMISSION = "replay.command.info";
    public static final String PLAY_PERMISSION = "replay.command.play";
    public static final String RECORD_START_PERMISSION = "replay.command.record.start";
    public static final String RECORD_STOP_PERMISSION = "replay.command.record.stop";
    public static final String CONTROL_PERMISSION = "replay.command.control";
    public static final String DELETE_PERMISSION = "replay.command.delete";
    public static final String METADATA_READ_PERMISSION = "replay.command.metadata.read";
    public static final String METADATA_WRITE_PERMISSION = "replay.command.metadata.write";

    /** Runtime values used by command handlers without exposing implementation services. */
    public record Context(
            ReplayFramework framework,
            Optional<ExampleViewerEnvironment> viewerEnvironment,
            Map<String, ReplayMetadataKey<?>> metadataKeys) {
        public Context {
            Objects.requireNonNull(framework, "framework");
            viewerEnvironment = Objects.requireNonNull(viewerEnvironment, "viewerEnvironment");
            metadataKeys = Map.copyOf(Objects.requireNonNull(metadataKeys, "metadataKeys"));
        }
    }

    private final JavaPlugin plugin;
    private final Supplier<Context> contextSupplier;
    private final ReplayRecordingCommands recordingCommands;
    private final ReplayDeleteCommand deleteCommand;

    private final ReplayListCommand listCommand;
    private final ReplayInfoCommand infoCommand;
    private final ReplayPlaybackCommands playbackCommands;
    private final ReplayMetadataCommands metadataCommands;

    /**
     * Creates a command composer. The context supplier may return {@code null}
     * while the runtime service is still bootstrapping.
     */
    public ReplayCommand(JavaPlugin plugin, Supplier<Context> contextSupplier) {
        this(plugin, contextSupplier, () -> Optional.empty());
    }

    /** Creates a command composer with a late-bound Example replay browser. */
    public ReplayCommand(
            JavaPlugin plugin,
            Supplier<Context> contextSupplier,
            Supplier<Optional<ReplayBrowser>> browserSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        Objects.requireNonNull(browserSupplier, "browserSupplier");
        this.recordingCommands = new ReplayRecordingCommands(plugin, contextSupplier);
        ReplaySuggestions suggestions = new ReplaySuggestions(
                contextSupplier,
                recordingCommands::activeSessions);
        recordingCommands.suggestions(suggestions);
        this.listCommand = new ReplayListCommand(plugin, contextSupplier, browserSupplier);
        this.infoCommand = new ReplayInfoCommand(plugin, contextSupplier, suggestions);
        this.playbackCommands = new ReplayPlaybackCommands(plugin, contextSupplier, suggestions);
        this.metadataCommands = new ReplayMetadataCommands(plugin, contextSupplier, suggestions);
        this.deleteCommand = new ReplayDeleteCommand(plugin, contextSupplier, suggestions);
    }

    /** Builds a fresh literal node for Paper's reloadable command registrar. */
    public LiteralCommandNode<CommandSourceStack> buildTree() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        root.then(listCommand.build());
        root.then(infoCommand.build());
        root.then(recordingCommands.build());
        playbackCommands.buildNodes().forEach(root::then);
        root.then(metadataCommands.build());
        root.then(deleteCommand.build());
        return root.build();
    }

    @Override
    public void close() {
        recordingCommands.close();
        deleteCommand.close();
    }

    /** Returns a permission predicate for Brigadier's server-side visibility gate. */
    public static Predicate<CommandSourceStack> permission(String node) {
        Objects.requireNonNull(node, "node");
        return source -> source != null
                && source.getSender() != null
                && source.getSender().hasPermission(node);
    }

    /** Rechecks a branch permission at execution time after Brigadier parsing. */
    public static void requirePermission(
            CommandSourceStack source,
            String node) throws CommandSyntaxException {
        if (!permission(node).test(source)) {
            throw syntax("You do not have permission to use this command");
        }
    }

    /** Resolves the current runtime context or reports that bootstrap is pending. */
    public static Context requireContext(
            Supplier<Context> contextSupplier) throws CommandSyntaxException {
        Context context = contextSupplier.get();
        if (context == null) {
            throw syntax("Replay Framework is still starting; please try again shortly");
        }
        return context;
    }

    /** Resolves a player sender without accepting console or command blocks. */
    public static Player requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
        CommandSender sender = source.getSender();
        if (sender instanceof Player player) {
            if (!player.isOnline()) {
                throw syntax("The player is no longer online");
            }
            return player;
        }
        throw syntax("This command is only available to players");
    }

    /** Creates a Brigadier syntax failure with a stable user-facing message. */
    public static CommandSyntaxException syntax(String message) {
        return new SimpleCommandExceptionType(new LiteralMessage(message)).create();
    }

    /** Completes an asynchronous service operation and returns its result to the Paper thread. */
    public static <T> void complete(
            JavaPlugin plugin,
            CommandSender sender,
            String operation,
            CompletionStage<T> stage,
            Consumer<T> success) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        if (stage == null) {
            reportFailure(plugin, sender, operation, new NullPointerException("completion stage"));
            return;
        }
        stage.whenComplete((value, failure) -> onMain(plugin, () -> {
            if (failure != null) {
                reportFailure(plugin, sender, operation, unwrap(failure));
            } else {
                try {
                    success.accept(value);
                } catch (Throwable callbackFailure) {
                    reportFailure(plugin, sender, operation, callbackFailure);
                }
            }
        }));
    }

    /** Schedules a callback on the server thread without blocking the caller. */
    public static void onMain(JavaPlugin plugin, Runnable action) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(action, "action");
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.FINE, "Paper task rejected during plugin shutdown");
        }
    }

    /** Sends a safe technical failure message and logs only stable diagnostics. */
    public static void reportFailure(
            JavaPlugin plugin,
            CommandSender sender,
            String operation,
            Throwable failure) {
        Throwable cause = unwrap(failure);
        String type = cause.getClass().getSimpleName();
        plugin.getLogger().warning(operation + " failed: " + type);
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        sender.sendMessage(Component.text(operation + " failed. Please try again or check the server log."));
    }

    /** Sends a neutral success message on the command sender. */
    public static void message(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text));
    }

    /** Unwraps CompletionStage wrappers without exposing internal exception text. */
    public static Throwable unwrap(Throwable failure) {
        Throwable current = failure == null ? new IllegalStateException("unknown failure") : failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

}
