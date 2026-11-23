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
 * <p>Players are looked at every tick, entities every sampling interval. A
 * subject that comes into the coverage gets its complete state published, so
 * its state track is complete from the start. When its signature changes,
 * only the changes since the last published reading are published. A subject
 * that leaves the coverage is forgotten and published completely again when
 * it returns.</p>
 *
 * <p>Runs on the server thread.</p>
 */
final class SubjectStates {

    private final ArgusServices services;
    private final UUID observationId;
    private final long intervalTicks;
    private final Map<TrackSubject, Published> known = new HashMap<>();
    private long ticksUntilSample;

    SubjectStates(ArgusServices services, UUID observationId, long intervalTicks) {
        this.services = Objects.requireNonNull(services, "services");
        this.observationId = Objects.requireNonNull(observationId, "observationId");
        if (intervalTicks < 1L) {
            throw new IllegalArgumentException("intervalTicks must be positive");
        }
        this.intervalTicks = intervalTicks;
    }

    /** Looks at the subjects of the coverage that are due this tick. */
    void tick(RecordingScope coverage) {
        sample(coverage, SubjectSample.Cadence.EVERY_TICK);
        if (--ticksUntilSample <= 0L) {
            ticksUntilSample = intervalTicks;
            sample(coverage, SubjectSample.Cadence.INTERVAL);
        }
    }

    private void sample(RecordingScope coverage, SubjectSample.Cadence cadence) {
        Map<String, List<CuboidRegion>> byWorld = new LinkedHashMap<>();
        for (CuboidRegion region : coverage.regions()) {
            byWorld.computeIfAbsent(region.world().asString(), ignored -> new ArrayList<>()).add(region);
        }
        Set<TrackSubject> present = new HashSet<>();
        for (Map.Entry<String, List<CuboidRegion>> world : byWorld.entrySet()) {
            for (SubjectSample sample : services.platform().subjects(world.getKey(), world.getValue(), cadence)) {
                TrackSubject subject = sample.subject().subject();
                if (!present.add(subject)) {
                    continue;
                }
                Published previous = known.get(subject);
                if (previous != null && previous.signature() == sample.signature()) {
                    continue;
                }
                SubjectSample.Reading reading = sample.reading().get();
                List<Object> packets = previous == null
                        ? reading.packets()
                        : reading.changesSince(previous.reading());
                for (Object packet : packets) {
                    services.publisher().publishState(observationId, sample.subject(), packet);
                }
                known.put(subject, new Published(cadence, sample.signature(), reading));
            }
        }
        known.entrySet().removeIf(entry ->
                entry.getValue().cadence() == cadence && !present.contains(entry.getKey()));
    }

    /** The last reading published for a subject. */
    private record Published(SubjectSample.Cadence cadence, long signature, SubjectSample.Reading reading) {
    }
}
