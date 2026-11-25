package dev.voldechse.replayframework.adapter.paper.checkpoint;

import dev.voldechse.replayframework.adapter.CheckpointEncoder;
import dev.voldechse.replayframework.adapter.IncompatibleAdapterException;
import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PacketDisposition;
import dev.voldechse.replayframework.adapter.PacketRegistry;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinition;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.ClientboundPacketDefinitionCatalog;
import dev.voldechse.replayframework.adapter.paper.packet.clientbound.PacketCheckpointFamily;
import dev.voldechse.replayframework.format.PacketPhase;
import dev.voldechse.replayframework.format.RawPacketFrame;
import dev.voldechse.replayframework.format.ReplayCheckpoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Assembles checkpoints from the detached state a version's
 * {@link SnapshotSource} takes and its {@link CheckpointCodec} encodes.
 *
 * <p>Assembly is version-independent: packets are ordered by family, world
 * and target, each is checked against the registry and the definitions, and
 * the frames are numbered. Subject and block states are encoded per subject
 * and per world. The live world state stays behind the two hooks, so the
 * result is deterministic for a given snapshot.</p>
 */
public final class CheckpointAssembler implements CheckpointEncoder {

    private static final PacketDescriptor.Direction CLIENTBOUND = PacketDescriptor.Direction.CLIENTBOUND;
    private static final PacketPhase PLAY = PacketPhase.PLAY;

    private static final Comparator<PacketBlueprint> PACKET_ORDER =
            Comparator.comparingInt((PacketBlueprint packet) -> packet.family().order())
                    .thenComparing(PacketBlueprint::worldKey)
                    .thenComparing(CheckpointAssembler::compareTargetKeys)
                    .thenComparing(PacketBlueprint::descriptorTypeName);

    private final PacketRegistry registry;
    private final ClientboundPacketDefinitionCatalog<?> catalog;
    private final SnapshotSource snapshots;
    private final CheckpointCodec codec;
    private final Executor executor;

    /**
     * @param registry the verified packet registry
     * @param catalog the clientbound definitions; they name each packet's family
     * @param snapshots the version's world access
     * @param codec the version's packet encoder
     * @param executor runs the assembly off the server thread
     * @throws IncompatibleAdapterException when a checkpoint packet has no family
     */
    public CheckpointAssembler(
            PacketRegistry registry,
            ClientboundPacketDefinitionCatalog<?> catalog,
            SnapshotSource snapshots,
            CheckpointCodec codec,
            Executor executor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.executor = Objects.requireNonNull(executor, "executor");
        validateGeneratorMatrix();
    }

    /**
     * Encodes one complete checkpoint without blocking the caller.
     *
     * <p>The initial checkpoint gets ordinal zero, later ones a provisional
     * ordinal; the core owns the ordinal sequence and replaces it.</p>
     */
    @Override
    public CompletionStage<ReplayCheckpoint> encode(CheckpointRequest request) {
        try {
            return snapshot(request).thenApplyAsync(snapshot -> {
                List<RawPacketFrame> frames = encodeFrames(
                        Objects.requireNonNull(snapshot, "snapshot"),
                        request.elapsedNanos(),
                        request.serverTick());
                int provisionalOrdinal = request.kind() == CheckpointKind.INITIAL ? 0 : 1;
                return new ReplayCheckpoint(provisionalOrdinal, request.elapsedNanos(), frames);
            }, executor);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public CompletionStage<CheckpointCapture> capture(CheckpointRequest request) {
        try {
            return snapshot(request).thenApplyAsync(snapshot -> {
                CheckpointSnapshot detached = Objects.requireNonNull(snapshot, "snapshot");
                CapturePoint point = detached.capturePoint().orElseGet(() -> new CapturePoint(
                        request.requestedCaptureTimeNanos(),
                        request.serverTick(),
                        Integer.MAX_VALUE));
                long elapsedNanos;
                try {
                    elapsedNanos = Math.subtractExact(
                            point.captureTimeNanos(), request.recordingStartCaptureTimeNanos());
                } catch (ArithmeticException overflow) {
                    throw new CheckpointAssemblyException("checkpoint capture time cannot be rebased", overflow);
                }
                if (elapsedNanos < request.elapsedNanos()) {
                    throw new CheckpointAssemblyException("checkpoint snapshot precedes its requested capture point");
                }
                return new CheckpointCapture(
                        point,
                        encodeFrames(detached, elapsedNanos, point.serverTick()),
                        encodeSubjects(detached, elapsedNanos, point.serverTick()),
                        encodeBlockStates(detached, elapsedNanos, point.serverTick()));
            }, executor);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** The checkpoint family the definition of a packet declares. */
    public PacketCheckpointFamily familyFor(String descriptorTypeName) {
        Objects.requireNonNull(descriptorTypeName, "descriptorTypeName");
        return catalog.find(descriptorTypeName)
                .flatMap(ClientboundPacketDefinition::checkpointFamily)
                .orElseThrow(() -> incompatible("no checkpoint family for descriptor " + descriptorTypeName));
    }

    private CompletionStage<CheckpointSnapshot> snapshot(CheckpointRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.kind() != CheckpointKind.INITIAL
                && request.kind() != CheckpointKind.PERIODIC
                && request.kind() != CheckpointKind.DIMENSION_CHANGE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "unsupported checkpoint kind: " + request.kind()));
        }
        try {
            validateGeneratorMatrix();
            return Objects.requireNonNull(snapshots.snapshot(request), "snapshot source returned null stage");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private List<RawPacketFrame> encodeFrames(CheckpointSnapshot snapshot, long elapsedNanos, long serverTick) {
        List<PacketBlueprint> blueprints = new ArrayList<>(snapshot.packets());
        // A snapshot may expose state that has no safe replay identity contract
        // yet. Such packets remain in the delta stream but cannot enter a
        // reconstructed checkpoint.
        blueprints.removeIf(blueprint -> !isCheckpointEligible(blueprint));
        blueprints.sort(PACKET_ORDER);

        List<RawPacketFrame> frames = new ArrayList<>(blueprints.size());
        for (int index = 0; index < blueprints.size(); index++) {
            PacketBlueprint blueprint = blueprints.get(index);
            EncodedPacket encoded = Objects.requireNonNull(codec.encode(blueprint), "codec returned null");
            validateEncodedPacket(blueprint, encoded);
            frames.add(new RawPacketFrame(
                    elapsedNanos,
                    serverTick,
                    index,
                    encoded.phase(),
                    encoded.packetId(),
                    encoded.payload(),
                    frameWorld(encoded)));
        }
        return List.copyOf(frames);
    }

    /**
     * Encodes the state of every subject. Its packets belong to no world and
     * are numbered within each subject, like checkpoint frames.
     */
    private List<SubjectState> encodeSubjects(CheckpointSnapshot snapshot, long elapsedNanos, long serverTick) {
        List<SubjectState> states = new ArrayList<>();
        for (CheckpointSnapshot.Subject subject : snapshot.subjects()) {
            List<RawPacketFrame> frames = new ArrayList<>(subject.packets().size());
            for (Object packet : subject.packets()) {
                CheckpointCodec.StatePacket encoded = codec.encodeStatePacket(packet);
                frames.add(new RawPacketFrame(
                        elapsedNanos, serverTick, frames.size(), PLAY, encoded.packetId(), encoded.payload()));
            }
            states.add(new SubjectState(subject.subject(), frames));
        }
        return List.copyOf(states);
    }

    /**
     * Encodes the state of the recorded blocks of every world. Its packets
     * belong to their world and are numbered within it.
     */
    private List<BlockStates> encodeBlockStates(CheckpointSnapshot snapshot, long elapsedNanos, long serverTick) {
        List<BlockStates> states = new ArrayList<>();
        for (Map.Entry<String, List<Object>> world : new TreeMap<>(snapshot.blockStates()).entrySet()) {
            List<RawPacketFrame> frames = new ArrayList<>(world.getValue().size());
            for (Object packet : world.getValue()) {
                CheckpointCodec.StatePacket encoded = codec.encodeStatePacket(packet);
                frames.add(new RawPacketFrame(
                        elapsedNanos, serverTick, frames.size(), PLAY, encoded.packetId(), encoded.payload(),
                        Optional.of(world.getKey())));
            }
            states.add(new BlockStates(world.getKey(), frames));
        }
        return List.copyOf(states);
    }

    /** Packets that concern no place, such as tab-list entries, apply in every world. */
    private Optional<String> frameWorld(EncodedPacket encoded) {
        boolean worldWide = catalog.find(encoded.descriptorTypeName())
                .map(ClientboundPacketDefinition::worldWide)
                .orElse(false);
        return worldWide ? Optional.empty() : Optional.of(encoded.worldKey());
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
            throw incompatible("checkpoint codec changed packet descriptor");
        }
        if (!blueprint.worldKey().equals(encoded.worldKey())
                || !blueprint.targetKey().equals(encoded.targetKey())) {
            throw incompatible("checkpoint codec changed checkpoint state identity");
        }
        if (encoded.phase() != PLAY || encoded.direction() != CLIENTBOUND) {
            throw incompatible("checkpoint packet is not PLAY clientbound");
        }
        PacketDescriptor descriptor = registry.find(PLAY, CLIENTBOUND, encoded.packetId())
                .orElseThrow(() -> incompatible("checkpoint packet is not registered: " + encoded.packetId()));
        if (!descriptor.typeName().equals(encoded.descriptorTypeName())) {
            throw incompatible("checkpoint packet ID does not match its descriptor");
        }
        if (descriptor.disposition() == PacketDisposition.CONTROL
                || descriptor.disposition() == PacketDisposition.UNSUPPORTED
                || !descriptor.replayable()
                || !descriptor.checkpointRelevant()) {
            throw incompatible("packet is not allowed in a checkpoint: " + descriptor.typeName());
        }
        if (familyFor(descriptor.typeName()) != blueprint.family() || blueprint.family() != encoded.family()) {
            throw incompatible("checkpoint packet family does not match its descriptor");
        }
    }

    /** Every checkpoint packet is PLAY clientbound, registered once, and has a family. */
    private void validateGeneratorMatrix() {
        Set<String> seen = new HashSet<>();
        for (PacketDescriptor descriptor : registry.descriptors()) {
            if (!descriptor.checkpointRelevant()) {
                continue;
            }
            if (!seen.add(descriptor.typeName())) {
                throw incompatible("duplicate checkpoint generator descriptor");
            }
            if (descriptor.phase() != PLAY || descriptor.direction() != CLIENTBOUND) {
                throw incompatible("checkpoint descriptor is not PLAY clientbound: " + descriptor.typeName());
            }
            familyFor(descriptor.typeName());
        }
    }

    /**
     * Orders target keys of the form {@code kind:numbers[:qualifier]} by their
     * numbers, so entity 9 comes before entity 10 and chunk {@code -1,2}
     * before {@code 0,2}; other keys compare as text.
     */
    static int compareTargetKeys(PacketBlueprint left, PacketBlueprint right) {
        return compareTargetKeys(left.targetKey(), right.targetKey());
    }

    static int compareTargetKeys(String left, String right) {
        int leftSeparator = left.indexOf(':');
        int rightSeparator = right.indexOf(':');
        String leftPrefix = leftSeparator < 0 ? left : left.substring(0, leftSeparator);
        String rightPrefix = rightSeparator < 0 ? right : right.substring(0, rightSeparator);
        int prefixComparison = leftPrefix.compareTo(rightPrefix);
        if (prefixComparison != 0) {
            return prefixComparison;
        }

        String leftBody = leftSeparator < 0 ? "" : left.substring(leftSeparator + 1);
        String rightBody = rightSeparator < 0 ? "" : right.substring(rightSeparator + 1);
        int leftQualifierSeparator = leftBody.indexOf(':');
        int rightQualifierSeparator = rightBody.indexOf(':');
        List<Integer> leftNumbers = numericParts(leftQualifierSeparator < 0
                ? leftBody
                : leftBody.substring(0, leftQualifierSeparator));
        List<Integer> rightNumbers = numericParts(rightQualifierSeparator < 0
                ? rightBody
                : rightBody.substring(0, rightQualifierSeparator));
        boolean leftNumeric = leftNumbers != null;
        boolean rightNumeric = rightNumbers != null;
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        if (leftNumeric) {
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
            String leftQualifier = leftQualifierSeparator < 0 ? "" : leftBody.substring(leftQualifierSeparator + 1);
            String rightQualifier = rightQualifierSeparator < 0 ? "" : rightBody.substring(rightQualifierSeparator + 1);
            int qualifierComparison = leftQualifier.compareTo(rightQualifier);
            if (qualifierComparison != 0) {
                return qualifierComparison;
            }
        } else {
            int bodyComparison = leftBody.compareTo(rightBody);
            if (bodyComparison != 0) {
                return bodyComparison;
            }
        }
        return left.compareTo(right);
    }

    private static List<Integer> numericParts(String value) {
        if (value.isEmpty()) {
            return null;
        }
        String[] parts = value.split(",", -1);
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

    private static IncompatibleAdapterException incompatible(String message) {
        return new IncompatibleAdapterException(message);
    }

    /** Failure raised when a checkpoint cannot keep its integrity. */
    static final class CheckpointAssemblyException extends RuntimeException {
        CheckpointAssemblyException(String message) {
            super(message);
        }

        CheckpointAssemblyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
