package dev.voldechse.replayframework.adapter.paper.checkpoint;

import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.CheckpointEncoder.CapturePoint;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The detached state of one checkpoint, taken by a {@link SnapshotSource}.
 * Native packets are the version's own packet objects; only the
 * {@link CheckpointCodec} of the same version reads them.
 *
 * @param capturePoint the barrier the state was detached at, when known
 * @param packets the scene
 * @param subjects the complete state of every subject in the scope
 * @param blockStates the state of the recorded blocks in the scope, by world key
 */
public record CheckpointSnapshot(
        Optional<CapturePoint> capturePoint,
        List<PacketBlueprint> packets,
        List<Subject> subjects,
        Map<String, List<Object>> blockStates) {

    public CheckpointSnapshot(List<PacketBlueprint> packets) {
        this(Optional.empty(), packets, List.of(), Map.of());
    }

    public CheckpointSnapshot(CapturePoint capturePoint, List<PacketBlueprint> packets) {
        this(Optional.of(Objects.requireNonNull(capturePoint, "capturePoint")), packets, List.of(), Map.of());
    }

    public CheckpointSnapshot {
        Objects.requireNonNull(capturePoint, "capturePoint");
        packets = List.copyOf(Objects.requireNonNull(packets, "packets"));
        subjects = List.copyOf(Objects.requireNonNull(subjects, "subjects"));
        blockStates = Map.copyOf(Objects.requireNonNull(blockStates, "blockStates"));
    }

    /**
     * The state of one subject.
     *
     * @param subject the subject
     * @param packets the native packets that restore its state, in order
     */
    public record Subject(CaptureSubject subject, List<Object> packets) {
        public Subject {
            Objects.requireNonNull(subject, "subject");
            packets = List.copyOf(Objects.requireNonNull(packets, "packets"));
        }
    }
}
