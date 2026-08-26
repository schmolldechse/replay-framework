package dev.voldechse.replayframework.example.command.subcommand;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import dev.voldechse.replayframework.example.command.argument.ReplayArguments;
import dev.voldechse.replayframework.example.command.argument.ReplaySuggestions;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Implements sender-bound, two-step replay deletion. */
public final class ReplayDeleteCommand implements AutoCloseable {
    private static final long TOKEN_LIFETIME_NANOS = 30_000_000_000L;
    private static final char[] TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final JavaPlugin plugin;
    private final Supplier<ReplayCommand.Context> contextSupplier;
    private final ReplaySuggestions suggestions;
    private final SecureRandom random = new SecureRandom();
    private final Map<SenderKey, PendingDelete> pending = new ConcurrentHashMap<>();

    public ReplayDeleteCommand(
            JavaPlugin plugin,
            Supplier<ReplayCommand.Context> contextSupplier,
            ReplaySuggestions suggestions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("delete")
                .requires(ReplayCommand.permission(ReplayCommand.DELETE_PERMISSION))
                .then(Commands.argument("replay", StringArgumentType.word())
                        .suggests(suggestions.replayIds())
                        .executes(this::executeRequest)
                        .then(Commands.literal("confirm")
                                .requires(ReplayCommand.permission(ReplayCommand.DELETE_PERMISSION))
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .executes(this::executeConfirm))));
    }

    @Override
    public void close() {
        pending.clear();
    }

    private int executeRequest(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.DELETE_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ReplayId replayId = parseReplayId(command);
        CommandSender sender = command.getSource().getSender();
        ReplayCommand.complete(
                plugin,
                sender,
                "Replay delete confirmation " + replayId.value(),
                runtime.framework().replays().find(replayId),
                found -> createConfirmation(sender, replayId, found));
        return 1;
    }

    private int executeConfirm(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.DELETE_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ReplayId replayId = parseReplayId(command);
        String token = StringArgumentType.getString(command, "token");
        CommandSender sender = command.getSource().getSender();
        SenderKey senderKey = SenderKey.of(sender);
        PendingDelete confirmation = pending.get(senderKey);
        if (confirmation == null
                || !confirmation.replayId().equals(replayId)
                || !confirmation.token().equals(token)
                || confirmation.expiresAtNanos() <= System.nanoTime()) {
            if (confirmation != null && confirmation.expiresAtNanos() <= System.nanoTime()) {
                pending.remove(senderKey, confirmation);
            }
            throw ReplayCommand.syntax("The delete confirmation token is invalid or expired");
        }
        if (!pending.remove(senderKey, confirmation)) {
            throw ReplayCommand.syntax("The delete confirmation token was already used");
        }

        ReplayCommand.complete(
                plugin,
                sender,
                "Replay delete " + replayId.value(),
                runtime.framework().replays().delete(replayId),
                ignored -> ReplayCommand.message(sender, "Replay deleted: " + replayId.value()));
        return 1;
    }

    private void createConfirmation(
            CommandSender sender,
            ReplayId replayId,
            Optional<ReplayMetadata> found) {
        if (found.isEmpty()) {
            ReplayCommand.message(sender, "Replay " + replayId.value() + " was not found.");
            return;
        }
        String token = nextToken();
        pending.put(
                SenderKey.of(sender),
                new PendingDelete(replayId, token, System.nanoTime() + TOKEN_LIFETIME_NANOS));
        ReplayCommand.message(sender, "Replay deletion is pending for 30 seconds.");
        ReplayCommand.message(sender, "Run /replay delete " + replayId.value()
                + " confirm " + token + " to continue.");
    }

    private String nextToken() {
        StringBuilder value = new StringBuilder(16);
        for (int index = 0; index < 16; index++) {
            value.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]);
        }
        return value.toString();
    }

    private static ReplayId parseReplayId(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try {
            return ReplayArguments.parseReplayId(
                    StringArgumentType.getString(command, "replay"));
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }
    }

    private record PendingDelete(ReplayId replayId, String token, long expiresAtNanos) {
    }

    private record SenderKey(String value) {
        private static SenderKey of(CommandSender sender) {
            if (sender instanceof Player player) {
                return new SenderKey("player:" + player.getUniqueId());
            }
            return new SenderKey("sender:" + sender.getName());
        }
    }
}
