package dev.voldechse.replayframework.example.command.subcommand;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.voldechse.replayframework.api.id.RecordingSessionId;
import dev.voldechse.replayframework.api.recording.CapturePolicy;
import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingRequest;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.api.recording.RecordingSession;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.example.command.ReplayCommand;
import dev.voldechse.replayframework.example.command.argument.ReplayArguments;
import dev.voldechse.replayframework.example.command.argument.ReplaySuggestions;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.key.Key;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Implements recording start and stop commands for the Example plugin. */
public final class ReplayRecordingCommands implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ReplayCommand.Context> contextSupplier;
    private final Map<RecordingSessionId, RecordingSession> activeSessions = new ConcurrentHashMap<>();
    private volatile ReplaySuggestions suggestions;

    public ReplayRecordingCommands(
            JavaPlugin plugin,
            Supplier<ReplayCommand.Context> contextSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
    }

    public void suggestions(ReplaySuggestions suggestions) {
        this.suggestions = Objects.requireNonNull(suggestions, "suggestions");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        ReplaySuggestions currentSuggestions = suggestions;
        if (currentSuggestions == null) {
            throw new IllegalStateException("Recording command suggestions are not initialized");
        }
        return Commands.literal("record")
                .then(buildStart())
                .then(Commands.literal("stop")
                        .requires(ReplayCommand.permission(ReplayCommand.RECORD_STOP_PERMISSION))
                        .then(Commands.argument("session", StringArgumentType.word())
                                .suggests(currentSuggestions.recordingSessions())
                                .executes(this::executeStop)));
    }

    public Collection<RecordingSession> activeSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            RecordingStatus status = entry.getValue().status();
            return status == RecordingStatus.AVAILABLE
                    || status == RecordingStatus.FAILED
                    || status == RecordingStatus.DELETING;
        });
        return Set.copyOf(activeSessions.values());
    }

    @Override
    public void close() {
        activeSessions.clear();
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildStart() {
        LiteralArgumentBuilder<CommandSourceStack> start = Commands.literal("start")
                .requires(ReplayCommand.permission(ReplayCommand.RECORD_START_PERMISSION));
        RequiredArgumentBuilder<CommandSourceStack, String> title =
                Commands.argument("title", StringArgumentType.string());
        RequiredArgumentBuilder<CommandSourceStack, String> description =
                Commands.argument("description", StringArgumentType.string());
        description.executes(this::executeStart);
        addParticipantBranch(description);
        addWorldBranch(description);
        title.then(description);
        start.then(title);
        return start;
    }

    private void addWorldBranch(ArgumentBuilder<CommandSourceStack, ?> parent) {
        LiteralArgumentBuilder<CommandSourceStack> world = Commands.literal("world");
        RequiredArgumentBuilder<CommandSourceStack, String> worldName =
                Commands.argument("world", StringArgumentType.word());
        worldName.executes(this::executeStart);
        addParticipantBranch(worldName);
        worldName.then(buildRegionBranch());
        world.then(worldName);
        parent.then(world);
    }

    private ArgumentBuilder<CommandSourceStack, ?> buildRegionBranch() {
        LiteralArgumentBuilder<CommandSourceStack> region = Commands.literal("region");
        RequiredArgumentBuilder<CommandSourceStack, Integer> minX =
                Commands.argument("minX", IntegerArgumentType.integer());
        RequiredArgumentBuilder<CommandSourceStack, Integer> minY =
                Commands.argument("minY", IntegerArgumentType.integer());
        RequiredArgumentBuilder<CommandSourceStack, Integer> minZ =
                Commands.argument("minZ", IntegerArgumentType.integer());
        RequiredArgumentBuilder<CommandSourceStack, Integer> maxX =
                Commands.argument("maxX", IntegerArgumentType.integer());
        RequiredArgumentBuilder<CommandSourceStack, Integer> maxY =
                Commands.argument("maxY", IntegerArgumentType.integer());
        RequiredArgumentBuilder<CommandSourceStack, Integer> maxZ =
                Commands.argument("maxZ", IntegerArgumentType.integer());
        maxZ.executes(this::executeStart);
        addParticipantBranch(maxZ);
        maxY.then(maxZ);
        maxX.then(maxY);
        minZ.then(maxX);
        minY.then(minZ);
        minX.then(minY);
        region.then(minX);
        return region;
    }

    private void addParticipantBranch(ArgumentBuilder<CommandSourceStack, ?> parent) {
        parent.then(Commands.literal("participants")
                .then(Commands.argument("participants", StringArgumentType.word())
                        .executes(this::executeStart)));
    }

    private int executeStart(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.RECORD_START_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        RecordingRequest request;
        try {
            request = buildRequest(command, runtime.framework().defaults());
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }

        CommandSender sender = command.getSource().getSender();
        ReplayCommand.complete(
                plugin,
                sender,
                "Recording start",
                runtime.framework().recordings().start(request),
                session -> {
                    activeSessions.put(session.id(), session);
                    ReplayCommand.message(
                            sender,
                            "Recording started: " + session.id().value()
                                    + " for replay " + session.replayId().value());
                });
        return 1;
    }

    private int executeStop(CommandContext<CommandSourceStack> command)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ReplayCommand.requirePermission(
                command.getSource(), ReplayCommand.RECORD_STOP_PERMISSION);
        ReplayCommand.Context runtime = ReplayCommand.requireContext(contextSupplier);
        RecordingSessionId sessionId;
        try {
            sessionId = ReplayArguments.parseRecordingSessionId(
                    StringArgumentType.getString(command, "session"));
        } catch (IllegalArgumentException failure) {
            throw ReplayCommand.syntax(failure.getMessage());
        }
        RecordingSession session = runtime.framework().recordings().active(sessionId).orElse(null);
        if (session == null) {
            throw ReplayCommand.syntax("Recording session is not active: " + sessionId.value());
        }

        CommandSender sender = command.getSource().getSender();
        ReplayCommand.complete(
                plugin,
                sender,
                "Recording stop " + sessionId.value(),
                session.stop(),
                terminal -> {
                    activeSessions.remove(sessionId);
                    ReplayCommand.message(
                            sender,
                            "Recording stopped: " + sessionId.value()
                                    + " (" + terminal.status() + ")");
                });
        return 1;
    }

    private RecordingRequest buildRequest(
            CommandContext<CommandSourceStack> command,
            dev.voldechse.replayframework.api.ReplayDefaults defaults) {
        String title = StringArgumentType.getString(command, "title");
        String description = StringArgumentType.getString(command, "description");
        RecordingScope.Builder scope = RecordingScope.builder();

        String worldRaw = optionalString(command, "world");
        Key world = null;
        if (worldRaw != null) {
            world = ReplayArguments.parseWorldKey(worldRaw);
            scope.addWorld(world);
        }

        Integer minX = optionalInteger(command, "minX");
        Integer minY = optionalInteger(command, "minY");
        Integer minZ = optionalInteger(command, "minZ");
        Integer maxX = optionalInteger(command, "maxX");
        Integer maxY = optionalInteger(command, "maxY");
        Integer maxZ = optionalInteger(command, "maxZ");
        boolean hasRegion = minX != null || minY != null || minZ != null
                || maxX != null || maxY != null || maxZ != null;
        if (hasRegion) {
            if (world == null || minX == null || minY == null || minZ == null
                    || maxX == null || maxY == null || maxZ == null) {
                throw new IllegalArgumentException("Region requires world and six coordinate values");
            }
            CuboidRegion region = ReplayArguments.parseRegion(
                    world, minX, minY, minZ, maxX, maxY, maxZ);
            scope.addRegion(region);
        }

        String participantsRaw = optionalString(command, "participants");
        Set<UUID> participants = participantsRaw == null
                ? Set.of()
                : ReplayArguments.parseParticipantList(participantsRaw);
        return RecordingRequest.builder()
                .title(title)
                .description(description)
                .scope(scope.build())
                .capturePolicy(CapturePolicy.builder().build())
                .budget(defaults.recordingBudget())
                .options(defaults.recordingOptions())
                .participants(participants)
                .build();
    }

    private static String optionalString(
            CommandContext<CommandSourceStack> command,
            String name) {
        try {
            return StringArgumentType.getString(command, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Integer optionalInteger(
            CommandContext<CommandSourceStack> command,
            String name) {
        try {
            return IntegerArgumentType.getInteger(command, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
