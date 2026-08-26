package dev.voldechse.replayframework.adapter.paper.v26_2.checkpoint;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.minecraft.network.protocol.Packet;

/**
 * Adapter-internal checkpoint orchestrator for the Paper 26.2 packet format.
 *
 * <p>The live Paper snapshot and the native packet codec are injected because
 * both touch Paper internals. Keeping those boundaries here makes the
 * checkpoint assembly deterministic and keeps mutable world state out of the
 * resulting replay format values.</p>
 */
public final class Paper26CheckpointEncoder implements CheckpointEncoder {

    /** The only adapter identifier accepted by this encoder. */
    public static final String ADAPTER_ID = "paper-26.2";

    private static final PacketDescriptor.Direction CLIENTBOUND =
            PacketDescriptor.Direction.CLIENTBOUND;
    private static final PacketPhase PLAY = PacketPhase.PLAY;

    private final PacketRegistry registry;
    private final SnapshotProvider snapshotProvider;
    private final NativePacketCodec nativePacketCodec;
    private final Executor executor;
    private final ChunkCheckpointEncoder chunkEncoder = new ChunkCheckpointEncoder();
    private final EntityCheckpointEncoder entityEncoder = new EntityCheckpointEncoder();
    private final GlobalStateCheckpointEncoder globalStateEncoder =
            new GlobalStateCheckpointEncoder();

    /**
     * Creates an encoder with explicit adapter-internal boundaries.
     *
     * @param registry verified Paper packet registry
     * @param snapshotProvider detached Paper-state provider
     * @param nativePacketCodec encoder for native 26.2 packet values
     * @param executor executor for detached checkpoint assembly
     */
    public Paper26CheckpointEncoder(
            PacketRegistry registry,
            SnapshotProvider snapshotProvider,
            NativePacketCodec nativePacketCodec,
            Executor executor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.snapshotProvider = Objects.requireNonNull(snapshotProvider, "snapshotProvider");
        this.nativePacketCodec = Objects.requireNonNull(nativePacketCodec, "nativePacketCodec");
        this.executor = Objects.requireNonNull(executor, "executor");
        validateGeneratorMatrix();
    }

    /**
     * Encodes one complete initial checkpoint without blocking the caller.
     *
     * <p>The adapter returns ordinal zero for the initial checkpoint and a
     * valid provisional ordinal for later checkpoints. Core owns the
     * session-global ordinal sequence and replaces the provisional value.</p>
     *
     * @param request validated checkpoint request
     * @return asynchronously encoded immutable checkpoint
     */
    @Override
    public CompletionStage<ReplayCheckpoint> encode(CheckpointRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.kind() != CheckpointKind.INITIAL
                && request.kind() != CheckpointKind.PERIODIC
                && request.kind() != CheckpointKind.DIMENSION_CHANGE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "unsupported checkpoint kind: " + request.kind()));
        }

        final CompletionStage<CheckpointSnapshot> snapshotStage;
        try {
            validateGeneratorMatrix();
            snapshotStage = Objects.requireNonNull(
                    snapshotProvider.snapshot(request),
                    "snapshotProvider returned null stage");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }

        try {
            return snapshotStage.thenApplyAsync(
                    snapshot -> encodeSnapshot(request, Objects.requireNonNull(snapshot, "snapshot")),
                    executor);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private ReplayCheckpoint encodeSnapshot(
            CheckpointRequest request,
            CheckpointSnapshot snapshot) {
        List<PacketBlueprint> blueprints = new ArrayList<>();
        blueprints.addAll(globalStateEncoder.encode(snapshot));
        blueprints.addAll(chunkEncoder.encode(snapshot));
        blueprints.addAll(entityEncoder.encode(snapshot));
        // Snapshot providers may expose state that is valuable to retain in
        // the recording but has no safe replay identity contract yet. Such
        // packets remain captured in the delta stream, but cannot enter a
        // reconstructed checkpoint.
        blueprints.removeIf(blueprint -> !isCheckpointEligible(blueprint));
        blueprints.sort(PACKET_ORDER);

        if (blueprints.size() > Integer.MAX_VALUE) {
            throw new CheckpointEncodingException("checkpoint contains too many packet frames");
        }

        List<RawPacketFrame> frames = new ArrayList<>(blueprints.size());
        for (int index = 0; index < blueprints.size(); index++) {
            PacketBlueprint blueprint = blueprints.get(index);
            EncodedPacket encoded = Objects.requireNonNull(
                    nativePacketCodec.encode(blueprint),
                    "nativePacketCodec returned null");
            validateEncodedPacket(blueprint, encoded);
            frames.add(new RawPacketFrame(
                    request.elapsedNanos(),
                    request.serverTick(),
                    index,
                    encoded.phase(),
                    encoded.packetId(),
                    encoded.payload()));
        }

        int provisionalOrdinal = request.kind() == CheckpointKind.INITIAL ? 0 : 1;
        return new ReplayCheckpoint(provisionalOrdinal, request.elapsedNanos(), frames);
    }

    private boolean isCheckpointEligible(PacketBlueprint blueprint) {
        return registry.descriptors().stream().anyMatch(descriptor ->
                descriptor.typeName().equals(blueprint.descriptorTypeName())
                        && descriptor.phase() == PLAY
                        && descriptor.direction() == CLIENTBOUND
                        && descriptor.replayable()
                        && descriptor.checkpointRelevant());
    }

    private void validateEncodedPacket(PacketBlueprint blueprint, EncodedPacket encoded) {
        if (!blueprint.descriptorTypeName().equals(encoded.descriptorTypeName())) {
            throw incompatible("native codec changed packet descriptor", null);
        }
        if (!blueprint.worldKey().equals(encoded.worldKey())
                || !blueprint.targetKey().equals(encoded.targetKey())) {
            throw incompatible("native codec changed checkpoint state identity", null);
        }
        if (encoded.phase() != PLAY || encoded.direction() != CLIENTBOUND) {
            throw incompatible("checkpoint packet is not PLAY clientbound", null);
        }

        PacketDescriptor descriptor = registry.find(PLAY, CLIENTBOUND, encoded.packetId())
                .orElseThrow(() -> incompatible(
                        "checkpoint packet is not registered: " + encoded.packetId(), null));
        if (!descriptor.typeName().equals(encoded.descriptorTypeName())) {
            throw incompatible("checkpoint packet ID does not match its descriptor", null);
        }
        if (descriptor.disposition() == PacketDisposition.CONTROL
                || descriptor.disposition() == PacketDisposition.UNSUPPORTED
                || !descriptor.replayable()
                || !descriptor.checkpointRelevant()) {
            throw incompatible(
                    "packet is not allowed in a Paper 26.2 checkpoint: " + descriptor.typeName(),
                    null);
        }
        if (familyFor(descriptor.typeName()) != blueprint.family()
                || blueprint.family() != encoded.family()) {
            throw incompatible("checkpoint packet family does not match its descriptor", null);
        }
    }

    private void validateGeneratorMatrix() {
        Set<String> seen = new HashSet<>();
        for (PacketDescriptor descriptor : registry.descriptors()) {
            if (!descriptor.checkpointRelevant()) {
                continue;
            }
            if (!seen.add(descriptor.typeName())) {
                throw incompatible("duplicate checkpoint generator descriptor", null);
            }
            if (descriptor.phase() != PLAY || descriptor.direction() != CLIENTBOUND) {
                throw incompatible(
                        "checkpoint descriptor is not PLAY clientbound: " + descriptor.typeName(),
                        null);
            }
            familyFor(descriptor.typeName());
        }
    }

    private static final Comparator<PacketBlueprint> PACKET_ORDER =
            Comparator.comparingInt((PacketBlueprint packet) -> packet.family().order())
                    .thenComparing(PacketBlueprint::worldKey)
                    .thenComparing(Paper26CheckpointEncoder::compareTargetKeys)
                    .thenComparing(PacketBlueprint::descriptorTypeName);

    private static int compareTargetKeys(PacketBlueprint left, PacketBlueprint right) {
        return compareTargetKeys(left.targetKey(), right.targetKey());
    }

    private static int compareTargetKeys(String left, String right) {
        int leftSeparator = left.indexOf(':');
        int rightSeparator = right.indexOf(':');
        String leftPrefix = leftSeparator < 0 ? left : left.substring(0, leftSeparator);
        String rightPrefix = rightSeparator < 0 ? right : right.substring(0, rightSeparator);
        int prefixComparison = leftPrefix.compareTo(rightPrefix);
        if (prefixComparison != 0) {
            return prefixComparison;
        }

        List<Integer> leftNumbers = numericParts(left, leftSeparator);
        List<Integer> rightNumbers = numericParts(right, rightSeparator);
        if (leftNumbers != null && rightNumbers != null) {
            int count = Math.min(leftNumbers.size(), rightNumbers.size());
            for (int index = 0; index < count; index++) {
                int comparison = Integer.compare(leftNumbers.get(index), rightNumbers.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            int lengthComparison = Integer.compare(leftNumbers.size(), rightNumbers.size());
            if (lengthComparison != 0) {
                return lengthComparison;
            }
        }
        return left.compareTo(right);
    }

    private static List<Integer> numericParts(String value, int separator) {
        if (separator < 0 || separator == value.length() - 1) {
            return null;
        }
        String[] parts = value.substring(separator + 1).split(",", -1);
        List<Integer> numbers = new ArrayList<>(parts.length);
        try {
            for (String part : parts) {
                numbers.add(Integer.valueOf(part));
            }
            return numbers;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Returns the checkpoint family for one stable Paper descriptor name.
     *
     * <p>This switch is deliberately explicit. A newly classified stateful
     * packet must fail adapter activation until a safe generator is assigned.</p>
     */
    public static CheckpointPacketFamily familyFor(String descriptorTypeName) {
        Objects.requireNonNull(descriptorTypeName, "descriptorTypeName");
        return switch (descriptorTypeName) {
            case "play.clientbound.minecraft:login",
                    "play.clientbound.minecraft:respawn",
                    "play.clientbound.minecraft:set_default_spawn_position" ->
                    CheckpointPacketFamily.DIMENSION_SPAWN;

            case "play.clientbound.minecraft:chunks_biomes",
                    "play.clientbound.minecraft:level_chunk_with_light",
                    "play.clientbound.minecraft:light_update",
                    "play.clientbound.minecraft:forget_level_chunk" ->
                    CheckpointPacketFamily.CHUNK_LIGHT;

            case "play.clientbound.minecraft:block_entity_data",
                    "play.clientbound.minecraft:block_update",
                    "play.clientbound.minecraft:section_blocks_update" ->
                    CheckpointPacketFamily.BLOCK_ENTITY;

            case "play.clientbound.minecraft:change_difficulty",
                    "play.clientbound.minecraft:game_rule_values",
                    "play.clientbound.minecraft:initialize_border",
                    "play.clientbound.minecraft:set_border_center",
                    "play.clientbound.minecraft:set_border_lerp_size",
                    "play.clientbound.minecraft:set_border_size",
                    "play.clientbound.minecraft:set_border_warning_delay",
                    "play.clientbound.minecraft:set_border_warning_distance",
                    "play.clientbound.minecraft:set_time",
                    "play.clientbound.minecraft:commands",
                    "play.clientbound.minecraft:custom_chat_completions",
                    "play.clientbound.minecraft:merchant_offers",
                    "play.clientbound.minecraft:mount_screen_open",
                    "play.clientbound.minecraft:open_screen",
                    "play.clientbound.minecraft:place_ghost_recipe",
                    "play.clientbound.minecraft:recipe_book_add",
                    "play.clientbound.minecraft:recipe_book_remove",
                    "play.clientbound.minecraft:recipe_book_settings",
                    "play.clientbound.minecraft:set_cursor_item",
                    "play.clientbound.minecraft:set_held_slot",
                    "play.clientbound.minecraft:set_player_inventory",
                    "play.clientbound.minecraft:player_info_remove",
                    "play.clientbound.minecraft:player_info_update",
                    "play.clientbound.minecraft:set_objective",
                    "play.clientbound.minecraft:set_display_objective",
                    "play.clientbound.minecraft:set_player_team",
                    "play.clientbound.minecraft:set_score",
                    "play.clientbound.minecraft:reset_score",
                    "play.clientbound.minecraft:boss_event",
                    "play.clientbound.minecraft:tab_list",
                    "play.clientbound.minecraft:update_advancements",
                    "play.clientbound.minecraft:update_recipes",
                    "play.clientbound.minecraft:update_tags",
                    "play.clientbound.minecraft:map_item_data",
                    "play.clientbound.minecraft:set_chunk_cache_center",
                    "play.clientbound.minecraft:set_chunk_cache_radius",
                    "play.clientbound.minecraft:set_simulation_distance",
                    "play.clientbound.minecraft:award_stats" ->
                    CheckpointPacketFamily.GLOBAL_STATE;

            case "play.clientbound.minecraft:add_entity" ->
                    CheckpointPacketFamily.ENTITY_SPAWN;

            case "play.clientbound.minecraft:entity_position_sync",
                    "play.clientbound.minecraft:move_entity_pos",
                    "play.clientbound.minecraft:move_entity_pos_rot",
                    "play.clientbound.minecraft:move_entity_rot",
                    "play.clientbound.minecraft:move_minecart_along_track",
                    "play.clientbound.minecraft:move_vehicle",
                    "play.clientbound.minecraft:remove_entities",
                    "play.clientbound.minecraft:rotate_head",
                    "play.clientbound.minecraft:set_camera",
                    "play.clientbound.minecraft:set_entity_data",
                    "play.clientbound.minecraft:set_entity_motion",
                    "play.clientbound.minecraft:set_experience",
                    "play.clientbound.minecraft:set_health",
                    "play.clientbound.minecraft:teleport_entity",
                    "play.clientbound.minecraft:update_attributes",
                    "play.clientbound.minecraft:projectile_power",
                    "play.clientbound.minecraft:waypoint",
                    "play.clientbound.minecraft:player_abilities",
                    "play.clientbound.minecraft:player_position",
                    "play.clientbound.minecraft:player_rotation",
                    "play.clientbound.minecraft:cooldown",
                    "play.clientbound.minecraft:container_close",
                    "play.clientbound.minecraft:container_set_content",
                    "play.clientbound.minecraft:container_set_data",
                    "play.clientbound.minecraft:container_set_slot" ->
                    CheckpointPacketFamily.ENTITY_METADATA;

            case "play.clientbound.minecraft:set_equipment" ->
                    CheckpointPacketFamily.EQUIPMENT;

            case "play.clientbound.minecraft:set_entity_link",
                    "play.clientbound.minecraft:set_passengers" ->
                    CheckpointPacketFamily.PASSENGERS;

            case "play.clientbound.minecraft:update_mob_effect",
                    "play.clientbound.minecraft:remove_mob_effect" ->
                    CheckpointPacketFamily.RUNNING_EFFECT;

            case "play.clientbound.minecraft:block_changed_ack" ->
                    throw incompatible(
                            "block_changed_ack is not a reconstructible checkpoint state", null);
            default -> throw incompatible(
                    "no checkpoint generator for descriptor " + descriptorTypeName, null);
        };
    }

    private static IncompatibleAdapterException incompatible(String message, Throwable cause) {
        return cause == null
                ? new IncompatibleAdapterException(message)
                : new IncompatibleAdapterException(message, cause);
    }

    /** Immutable detached state passed from Paper world access to assembly. */
    public record CheckpointSnapshot(List<PacketBlueprint> packets) {
        public CheckpointSnapshot {
            Objects.requireNonNull(packets, "packets");
            List<PacketBlueprint> copied = new ArrayList<>(packets.size());
            for (PacketBlueprint packet : packets) {
                copied.add(Objects.requireNonNull(packet, "packets contains null"));
            }
            packets = List.copyOf(copied);
        }
    }

    /** One detached native packet before the Paper codec assigns its wire ID. */
    public record PacketBlueprint(
            String descriptorTypeName,
            CheckpointPacketFamily family,
            String worldKey,
            String targetKey,
            Packet<?> nativePacket) {
        public PacketBlueprint {
            descriptorTypeName = requireStableText(descriptorTypeName, "descriptorTypeName");
            Objects.requireNonNull(family, "family");
            worldKey = requireStableText(worldKey, "worldKey");
            targetKey = requireStableText(targetKey, "targetKey");
            Objects.requireNonNull(nativePacket, "nativePacket");
        }
    }

    /** Raw result produced by the version-bound native packet codec. */
    public record EncodedPacket(
            String descriptorTypeName,
            PacketPhase phase,
            PacketDescriptor.Direction direction,
            int packetId,
            CheckpointPacketFamily family,
            String worldKey,
            String targetKey,
            byte[] payload) {
        public EncodedPacket {
            descriptorTypeName = requireStableText(descriptorTypeName, "descriptorTypeName");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(direction, "direction");
            if (packetId < 0) {
                throw new IllegalArgumentException("packetId must not be negative");
            }
            Objects.requireNonNull(family, "family");
            worldKey = requireStableText(worldKey, "worldKey");
            targetKey = requireStableText(targetKey, "targetKey");
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /** Adapter-internal asynchronous detached-world snapshot boundary. */
    @FunctionalInterface
    public interface SnapshotProvider {
        CompletionStage<CheckpointSnapshot> snapshot(CheckpointRequest request);
    }

    /** Adapter-internal Paper 26.2 packet codec boundary. */
    @FunctionalInterface
    public interface NativePacketCodec {
        EncodedPacket encode(PacketBlueprint blueprint);
    }

    /** Total order used for checkpoint family assembly. */
    public enum CheckpointPacketFamily {
        DIMENSION_SPAWN(0),
        CHUNK_LIGHT(1),
        BLOCK_ENTITY(2),
        GLOBAL_STATE(3),
        ENTITY_SPAWN(4),
        ENTITY_METADATA(5),
        EQUIPMENT(6),
        PASSENGERS(7),
        RUNNING_EFFECT(8);

        private final int order;

        CheckpointPacketFamily(int order) {
            this.order = order;
        }

        int order() {
            return order;
        }
    }

    /** Failure raised when a checkpoint cannot preserve adapter integrity. */
    static final class CheckpointEncodingException extends RuntimeException {
        CheckpointEncodingException(String message) {
            super(message);
        }

        CheckpointEncodingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String requireStableText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new IllegalArgumentException(field + " must not contain whitespace or control characters");
            }
        }
        return value;
    }
}
