package dev.voldechse.replayframework.example.command.subcommand;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.playback.PlaybackSession;
import dev.voldechse.replayframework.api.playback.PlaybackSpeed;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import dev.voldechse.replayframework.example.command.argument.ReplayArguments;
import dev.voldechse.replayframework.example.command.argument.ReplaySuggestions;
import dev.voldechse.replayframework.example.ui.ReplayBrowser;
import dev.voldechse.replayframework.example.viewer.ExampleViewerEnvironment;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.function.Supplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Implements playback opening and controls for one Example viewer. */
public final class ReplayPlaybackCommands {
    private final JavaPlugin plugin;
    private final Supplier<ReplayCommand.Context> contextSupplier;
    private final ReplaySuggestions suggestions;
    private final Supplier<Optional<ReplayBrowser>> browserSupplier;

    public ReplayPlaybackCommands(
            JavaPlugin plugin,
            Supplier<ReplayCommand.Context> contextSupplier,
            ReplaySuggestions suggestions) {
        this(plugin, contextSupplier, suggestions, Optional::empty);
    }

    public ReplayPlaybackCommands(
            JavaPlugin plugin,
            Supplier<ReplayCommand.Context> contextSupplier,
            ReplaySuggestions suggestions,
            Supplier<Optional<ReplayBrowser>> browserSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
        this.browserSupplier = Objects.requireNonNull(browserSupplier, "browserSupplier");
    }

    public List<LiteralArgumentBuilder<CommandSourceStack>> buildNodes() {
        return List.of(
                Commands.literal("play")
                .requires(ReplayCommand.permission(ReplayCommand.PLAY_PERMISSION))
                .then(Commands.argument("replay", StringArgumentType.word())
                        .suggests(suggestions.replayIds())
                        .executes(this::executeOpen)),
                Commands.literal("pause")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .executes(this::executePause),
                Commands.literal("resume")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .executes(this::executeResume),
                Commands.literal("restart")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .executes(this::executeRestart),
                Commands.literal("seek")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(this::executeSeek)),
                Commands.literal("forward")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(this::executeForward)),
                Commands.literal("rewind")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(this::executeRewind)),
                Commands.literal("speed")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .then(Commands.argument("speed", StringArgumentType.word())
                                .executes(this::executeSpeed)),
                Commands.literal("leave")
                        .requires(ReplayCommand.permission(ReplayCommand.CONTROL_PERMISSION))
                        .executes(this::executeLeave));
    }

    private int executeOpen(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.PLAY_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ExampleViewerEnvironment environment = requireEnvironment(runtime);
        Player player = ReplayCommand.requirePlayer(command.getSource());
        ReplayId replayId;
        try {
            replayId = ReplayArguments.parseReplayId(
                    StringArgumentType.getString(command, "replay"));
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }
        CommandSender sender = command.getSource().getSender();
        Optional<ReplayBrowser> browser = Optional.ofNullable(browserSupplier.get())
                .orElse(Optional.empty());
        ReplayCommand.complete(
                plugin,
                sender,
                "Playback open " + replayId.value(),
                browser.map(value -> value.openDirect(player, replayId))
                        .orElseGet(() -> environment.open(player, replayId)),
                session -> ReplayCommand.message(
                        sender,
                        "Playback opened for " + session.replayId().value()));
        return 1;
    }

    private int executePause(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.CONTROL_PERMISSION);
        PlaybackSession session = activeSession(command);
        CommandSender sender = command.getSource().getSender();
        try {
            session.pause();
            ReplayCommand.message(sender, "Playback paused.");
        } catch (RuntimeException failure) {
            ReplayCommand.reportFailure(plugin, sender, "Playback pause", failure);
        }
        return 1;
    }

    private int executeResume(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.CONTROL_PERMISSION);
        PlaybackSession session = activeSession(command);
        CommandSender sender = command.getSource().getSender();
        try {
            session.play();
            ReplayCommand.message(sender, "Playback resumed.");
        } catch (RuntimeException failure) {
            ReplayCommand.reportFailure(plugin, sender, "Playback resume", failure);
        }
        return 1;
    }

    private int executeRestart(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.CONTROL_PERMISSION);
        PlaybackSession session = activeSession(command);
        completeSnapshot(command.getSource().getSender(), "Playback restart", session.restart());
        return 1;
    }

    private int executeSeek(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.CONTROL_PERMISSION);
        PlaybackSession session = activeSession(command);
        Duration position;
        try {
            position = ReplayArguments.parseTime(
                    StringArgumentType.getString(command, "time"), true);
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }
        completeSnapshot(
                command.getSource().getSender(),
                "Playback seek",
                session.seekTo(position));
        return 1;
    }

    private int executeForward(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return executeRelative(command, false);
    }

    private int executeRewind(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return executeRelative(command, true);
    }

    private int executeRelative(
            CommandContext<CommandSourceStack> command,
            boolean rewind) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.CONTROL_PERMISSION);
        PlaybackSession session = activeSession(command);
        Duration duration;
        try {
            duration = ReplayArguments.parseTime(
                    StringArgumentType.getString(command, "duration"), false);
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }
        if (rewind) {
            duration = duration.negated();
        }
        completeSnapshot(
                command.getSource().getSender(),
                rewind ? "Playback rewind" : "Playback forward",
                session.seekBy(duration));
        return 1;
    }

    private int executeSpeed(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.CONTROL_PERMISSION);
        PlaybackSession session = activeSession(command);
        PlaybackSpeed speed;
        try {
            speed = ReplayArguments.parseSpeed(StringArgumentType.getString(command, "speed"));
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }
        CommandSender sender = command.getSource().getSender();
        try {
            session.speed(speed);
            ReplayCommand.message(sender, "Playback speed set to " + speed + ".");
        } catch (RuntimeException failure) {
            ReplayCommand.reportFailure(plugin, sender, "Playback speed", failure);
        }
        return 1;
    }

    private int executeLeave(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.CONTROL_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ExampleViewerEnvironment environment = requireEnvironment(runtime);
        Player player = ReplayCommand.requirePlayer(command.getSource());
        ReplayCommand.complete(
                plugin,
                command.getSource().getSender(),
                "Leave playback",
                environment.leave(player),
                ignored -> ReplayCommand.message(command.getSource().getSender(), "Playback left."));
        return 1;
    }

    private PlaybackSession activeSession(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ExampleViewerEnvironment environment = requireEnvironment(runtime);
        Player player = ReplayCommand.requirePlayer(command.getSource());
        Optional<PlaybackSession> session = environment.playback(player.getUniqueId());
        if (session.isEmpty()) {
            throw ReplayCommand.syntax("The player has no active playback session");
        }
        return session.get();
    }

    private static ExampleViewerEnvironment requireEnvironment(ReplayCommand.Context runtime)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return runtime.viewerEnvironment()
                .orElseThrow(() -> ReplayCommand.syntax(
                        "Viewer commands are unavailable until the Example viewer is configured"));
    }

    private void completeSnapshot(
            CommandSender sender,
            String operation,
            java.util.concurrent.CompletionStage<?> stage) {
        ReplayCommand.complete(plugin, sender, operation, stage, ignored ->
                ReplayCommand.message(sender, operation + " applied."));
    }
}
