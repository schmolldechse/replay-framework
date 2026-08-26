package dev.voldechse.replayframework.example.command.subcommand;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import dev.voldechse.replayframework.example.command.argument.ReplayArguments;
import dev.voldechse.replayframework.example.command.argument.ReplaySuggestions;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Implements the public replay metadata information command. */
public final class ReplayInfoCommand {
    private final JavaPlugin plugin;
    private final Supplier<ReplayCommand.Context> contextSupplier;
    private final ReplaySuggestions suggestions;

    public ReplayInfoCommand(
            JavaPlugin plugin,
            Supplier<ReplayCommand.Context> contextSupplier,
            ReplaySuggestions suggestions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("info")
                .requires(ReplayCommand.permission(ReplayCommand.INFO_PERMISSION))
                .then(Commands.argument("replay", StringArgumentType.word())
                        .suggests(suggestions.replayIds())
                        .executes(this::execute));
    }

    private int execute(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                context.getSource(), ReplayCommand.INFO_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ReplayId replayId;
        try {
            replayId = ReplayArguments.parseReplayId(
                    StringArgumentType.getString(context, "replay"));
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }
        CommandSender sender = context.getSource().getSender();
        ReplayCommand.complete(
                plugin,
                sender,
                "Replay information for " + replayId.value(),
                runtime.framework().replays().find(replayId),
                result -> sendResult(sender, replayId, result));
        return 1;
    }

    private static void sendResult(
            CommandSender sender,
            ReplayId replayId,
            Optional<ReplayMetadata> result) {
        if (result.isEmpty()) {
            ReplayCommand.message(sender, "Replay " + replayId.value() + " was not found.");
            return;
        }
        ReplayMetadata metadata = result.get();
        ReplayCommand.message(sender, "Replay " + metadata.replayId().value());
        ReplayCommand.message(sender, "Title: " + metadata.title());
        ReplayCommand.message(sender, "Description: " + metadata.description());
        ReplayCommand.message(sender, "Metadata revision: " + metadata.revision());
        if (!metadata.values().isEmpty()) {
            ReplayCommand.message(sender, "Custom metadata: " + metadata.values());
        }
    }
}
