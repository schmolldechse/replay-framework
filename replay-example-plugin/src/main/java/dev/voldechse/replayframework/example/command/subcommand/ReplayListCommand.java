package dev.voldechse.replayframework.example.command.subcommand;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import java.util.Objects;
import java.util.function.Supplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Implements the public replay catalog list command. */
public final class ReplayListCommand {
    private final JavaPlugin plugin;
    private final Supplier<ReplayCommand.Context> contextSupplier;

    public ReplayListCommand(
            JavaPlugin plugin,
            Supplier<ReplayCommand.Context> contextSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("list")
                .requires(ReplayCommand.permission(ReplayCommand.LIST_PERMISSION))
                .executes(this::execute);
    }

    private int execute(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                context.getSource(), ReplayCommand.LIST_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        CommandSender sender = context.getSource().getSender();
        ReplayQuery query = ReplayQuery.builder()
                .status(RecordingStatus.AVAILABLE)
                .orderBy(ReplayQuery.SortField.CREATED_AT, ReplayQuery.SortDirection.DESCENDING)
                .build();
        ReplayCommand.complete(
                plugin,
                sender,
                "Replay list",
                runtime.framework().replays().query(query),
                page -> sendPage(sender, page));
        return 1;
    }

    private static void sendPage(CommandSender sender, ReplayPage<ReplayMetadata> page) {
        if (page.items().isEmpty()) {
            ReplayCommand.message(sender, "No available replays found.");
            return;
        }
        ReplayCommand.message(sender, "Available replays:");
        for (ReplayMetadata metadata : page.items()) {
            ReplayCommand.message(
                    sender,
                    metadata.replayId().value()
                            + " | " + metadata.title()
                            + " | " + metadata.description()
                            + " | custom keys: " + metadata.values().keySet());
        }
        if (page.hasNext()) {
            ReplayCommand.message(sender, "More replays are available in the replay browser.");
        }
    }
}
