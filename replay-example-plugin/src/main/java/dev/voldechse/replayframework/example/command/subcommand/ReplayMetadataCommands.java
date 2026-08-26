package dev.voldechse.replayframework.example.command.subcommand;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.MetadataMutation;
import dev.voldechse.replayframework.api.metadata.MetadataRevision;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataService;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import dev.voldechse.replayframework.example.command.argument.ReplayArguments;
import dev.voldechse.replayframework.example.command.argument.ReplaySuggestions;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Implements typed Example metadata reads, mutations and history output. */
public final class ReplayMetadataCommands {
    private final JavaPlugin plugin;
    private final Supplier<ReplayCommand.Context> contextSupplier;
    private final ReplaySuggestions suggestions;

    public ReplayMetadataCommands(
            JavaPlugin plugin,
            Supplier<ReplayCommand.Context> contextSupplier,
            ReplaySuggestions suggestions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("metadata")
                .then(buildGet())
                .then(buildHistory())
                .then(buildMutation("set", true))
                .then(buildMutation("remove", false));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildGet() {
        return Commands.literal("get")
                .requires(ReplayCommand.permission(ReplayCommand.METADATA_READ_PERMISSION))
                .then(Commands.argument("replay", StringArgumentType.word())
                        .suggests(suggestions.replayIds())
                        .executes(this::executeGetAll)
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(suggestions.metadataKeys())
                                .executes(this::executeGetKey)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildHistory() {
        return Commands.literal("history")
                .requires(ReplayCommand.permission(ReplayCommand.METADATA_READ_PERMISSION))
                .then(Commands.argument("replay", StringArgumentType.word())
                        .suggests(suggestions.replayIds())
                        .executes(this::executeHistory));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildMutation(
            String literal,
            boolean setValue) {
        var key = Commands.argument("key", StringArgumentType.word())
                .suggests(suggestions.metadataKeys());
        if (setValue) {
            key.then(Commands.argument("value", StringArgumentType.greedyString())
                    .executes(command -> executeMutation(command, true)));
        } else {
            key.executes(command -> executeMutation(command, false));
        }
        return Commands.literal(literal)
                .requires(ReplayCommand.permission(ReplayCommand.METADATA_WRITE_PERMISSION))
                .then(Commands.argument("replay", StringArgumentType.word())
                        .suggests(suggestions.replayIds())
                        .then(key));
    }

    private int executeGetAll(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.METADATA_READ_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ReplayId replayId = parseReplayId(command);
        CommandSender sender = command.getSource().getSender();
        ReplayCommand.complete(
                plugin,
                sender,
                "Metadata get " + replayId.value(),
                runtime.framework().metadata().get(replayId),
                metadata -> sendMetadata(sender, metadata));
        return 1;
    }

    private int executeGetKey(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.METADATA_READ_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ReplayId replayId = parseReplayId(command);
        ReplayMetadataKey<?> key = resolveKey(runtime.metadataKeys(), command);
        CommandSender sender = command.getSource().getSender();
        ReplayCommand.complete(
                plugin,
                sender,
                "Metadata get " + replayId.value(),
                runtime.framework().metadata().get(replayId),
                metadata -> sendKey(sender, metadata, key));
        return 1;
    }

    private int executeHistory(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.METADATA_READ_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ReplayId replayId = parseReplayId(command);
        CommandSender sender = command.getSource().getSender();
        ReplayCommand.complete(
                plugin,
                sender,
                "Metadata history " + replayId.value(),
                runtime.framework().metadata().history(replayId),
                revisions -> sendHistory(sender, revisions));
        return 1;
    }

    private int executeMutation(
            CommandContext<CommandSourceStack> command,
            boolean setValue) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.METADATA_WRITE_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        ReplayId replayId = parseReplayId(command);
        ReplayMetadataKey<?> key = resolveKey(runtime.metadataKeys(), command);
        Object parsedValue = null;
        if (setValue) {
            String value = StringArgumentType.getString(command, "value");
            try {
                parsedValue = ReplayArguments.parseMetadataValue(key, value);
            } catch (IllegalArgumentException failure) {
                throw ReplayCommand.syntax(failure.getMessage());
            }
        }
        Object finalParsedValue = parsedValue;
        ReplayMetadataService metadataService = runtime.framework().metadata();
        java.util.concurrent.CompletionStage<ReplayMetadata> operation = metadataService
                .get(replayId)
                .thenCompose(current -> {
                    MetadataMutation mutation = setValue
                            ? setMutation(replayId, current.revision(), key, finalParsedValue)
                            : removeMutation(replayId, current.revision(), key);
                    return metadataService.apply(mutation);
                });
        ReplayCommand.complete(
                plugin,
                command.getSource().getSender(),
                setValue ? "Metadata set " + replayId.value() : "Metadata remove " + replayId.value(),
                operation,
                result -> ReplayCommand.message(
                        command.getSource().getSender(),
                        "Metadata updated to revision " + result.revision() + "."));
        return 1;
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

    private static ReplayMetadataKey<?> resolveKey(
            Map<String, ReplayMetadataKey<?>> keys,
            CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String raw = StringArgumentType.getString(command, "key").toLowerCase(Locale.ROOT);
        ReplayMetadataKey<?> key = keys.get(raw);
        if (key == null) {
            throw ReplayCommand.syntax("Metadata key is not registered by the Example plugin");
        }
        return key;
    }

    private static void sendMetadata(CommandSender sender, ReplayMetadata metadata) {
        ReplayCommand.message(sender, "Metadata for " + metadata.replayId().value());
        ReplayCommand.message(sender, "Title: " + metadata.title());
        ReplayCommand.message(sender, "Description: " + metadata.description());
        ReplayCommand.message(sender, "Revision: " + metadata.revision());
        ReplayCommand.message(sender, "Values: " + metadata.values());
    }

    private static void sendKey(
            CommandSender sender,
            ReplayMetadata metadata,
            ReplayMetadataKey<?> key) {
        Optional<?> value = metadata.value(key);
        if (value.isEmpty()) {
            ReplayCommand.message(sender, "Metadata key is not set: " + key.key().asString());
            return;
        }
        ReplayCommand.message(sender, key.key().asString() + ": " + encodeValue(key, value.get()));
    }

    private static void sendHistory(CommandSender sender, List<MetadataRevision> revisions) {
        if (revisions.isEmpty()) {
            ReplayCommand.message(sender, "Metadata history is empty.");
            return;
        }
        for (MetadataRevision revision : revisions) {
            ReplayCommand.message(
                    sender,
                    "Revision " + revision.revision()
                            + " | " + revision.timestamp()
                            + " | " + revision.operation()
                            + " | " + revision.snapshot().values());
        }
    }

    private static MetadataMutation setMutation(
            ReplayId replayId,
            long revision,
            ReplayMetadataKey<?> rawKey,
            Object value) {
        return setMutationUnchecked(replayId, revision, rawKey, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> MetadataMutation setMutationUnchecked(
            ReplayId replayId,
            long revision,
            ReplayMetadataKey<?> rawKey,
            Object value) {
        ReplayMetadataKey<T> key = (ReplayMetadataKey<T>) rawKey;
        return MetadataMutation.builder(replayId)
                .expectedRevision(revision)
                .put(key, (T) value)
                .build();
    }

    private static String encodeValue(ReplayMetadataKey<?> rawKey, Object value) {
        return encodeValueUnchecked(rawKey, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> String encodeValueUnchecked(
            ReplayMetadataKey<?> rawKey,
            Object value) {
        ReplayMetadataKey<T> key = (ReplayMetadataKey<T>) rawKey;
        return key.codec().toJson((T) value);
    }

    private static MetadataMutation removeMutation(
            ReplayId replayId,
            long revision,
            ReplayMetadataKey<?> key) {
        return MetadataMutation.builder(replayId)
                .expectedRevision(revision)
                .remove(key)
                .build();
    }
}
