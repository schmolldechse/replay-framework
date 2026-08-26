package dev.voldechse.replayframework.example.command.argument;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/** Non-blocking Brigadier suggestion providers for the Example command tree. */
public final class ReplaySuggestions {
    private final Supplier<ReplayCommand.Context> contextSupplier;
    private final Supplier<Collection<RecordingSession>> recordingSessions;

    public ReplaySuggestions(
            Supplier<ReplayCommand.Context> contextSupplier,
            Supplier<Collection<RecordingSession>> recordingSessions) {
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.recordingSessions = Objects.requireNonNull(recordingSessions, "recordingSessions");
    }

    public SuggestionProvider<CommandSourceStack> replayIds() {
        return this::suggestReplayIds;
    }

    public SuggestionProvider<CommandSourceStack> recordingSessions() {
        return this::suggestRecordingSessions;
    }

    public SuggestionProvider<CommandSourceStack> metadataKeys() {
        return this::suggestMetadataKeys;
    }

    private CompletableFuture<Suggestions> suggestReplayIds(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        ReplayCommand.Context runtime = contextSupplier.get();
        if (runtime == null) {
            return builder.buildFuture();
        }
        ReplayQuery query = ReplayQuery.builder()
                .status(RecordingStatus.AVAILABLE)
                .orderBy(ReplayQuery.SortField.CREATED_AT, ReplayQuery.SortDirection.DESCENDING)
                .limit(50)
                .build();
        try {
            return runtime.framework().replays().query(query)
                    .handle((page, failure) -> {
                        if (failure == null && page != null) {
                            addReplaySuggestions(builder, page);
                        }
                        return builder.build();
                    })
                    .toCompletableFuture();
        } catch (RuntimeException failure) {
            return builder.buildFuture();
        }
    }

    private CompletableFuture<Suggestions> suggestRecordingSessions(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        try {
            for (RecordingSession session : recordingSessions.get()) {
                if (session == null) {
                    continue;
                }
                RecordingStatus status = session.status();
                if (status == RecordingStatus.INITIALIZING
                        || status == RecordingStatus.RECORDING
                        || status == RecordingStatus.FINALIZING) {
                    builder.suggest(session.id().value().toString());
                }
            }
        } catch (RuntimeException ignored) {
            // Suggestions must remain best-effort and never fail command parsing.
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestMetadataKeys(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        ReplayCommand.Context runtime = contextSupplier.get();
        if (runtime != null) {
            runtime.metadataKeys().keySet().forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private static void addReplaySuggestions(
            SuggestionsBuilder builder,
            ReplayPage<ReplayMetadata> page) {
        for (ReplayMetadata metadata : page.items()) {
            builder.suggest(metadata.replayId().value().toString());
        }
    }
}
