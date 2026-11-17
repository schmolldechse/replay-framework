package dev.voldechse.replayframework.adapter.paper.argus;

import dev.voldechse.replayframework.api.recording.CuboidRegion;
import dev.voldechse.replayframework.api.recording.RecordingScope;
import dev.voldechse.replayframework.format.TrackSubject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The subjects of one watch: players and entities with state of their own
 * inside what the watch's eyes cover.
 *
 * <p>Every sampling interval it reads the subjects of the coverage. A subject
 * that comes into the coverage gets its complete state published, so its
 * state track is complete from the start. A sampled subject is published again
 * whenever its state changed. An observed subject, a player, reports its own
 * changes through its connection. A subject that leaves the coverage is
 * forgotten and published again when it returns.</p>
 *
 * <p>Runs on the server thread.</p>
 */
final class SubjectStates {

    private final ArgusServices services;
    private final UUID observationId;
    private final long intervalTicks;
    private final Map<TrackSubject, Long> known = new HashMap<>();
    private long ticksUntilSample;

    SubjectStates(ArgusServices services, UUID observationId, long intervalTicks) {
        this.services = Objects.requireNonNull(services, "services");
        this.observationId = Objects.requireNonNull(observationId, "observationId");
        if (intervalTicks < 1L) {
            throw new IllegalArgumentException("intervalTicks must be positive");
        }
        this.intervalTicks = intervalTicks;
    }

    /** Samples the subjects of the coverage when the interval has passed. */
    void tick(RecordingScope coverage) {
        if (--ticksUntilSample > 0L) {
            return;
        }
        ticksUntilSample = intervalTicks;
        Map<String, List<CuboidRegion>> byWorld = new LinkedHashMap<>();
        for (CuboidRegion region : coverage.regions()) {
            byWorld.computeIfAbsent(region.world().asString(), ignored -> new ArrayList<>()).add(region);
        }
        Set<TrackSubject> present = new HashSet<>();
        for (Map.Entry<String, List<CuboidRegion>> world : byWorld.entrySet()) {
            for (SubjectSample sample : services.platform().subjects(world.getKey(), world.getValue())) {
                TrackSubject subject = sample.subject().subject();
                if (!present.add(subject)) {
                    continue;
                }
                Long previous = known.put(subject, sample.signature());
                boolean changed = sample.source() == SubjectSample.Source.SAMPLED
                        && previous != null && previous != sample.signature();
                if (previous == null || changed) {
                    for (Object packet : sample.packets().get()) {
                        services.publisher().publishState(observationId, sample.subject(), packet);
                    }
                }
            }
        }
        known.keySet().retainAll(present);
    }
}
