package dev.voldechse.replayframework.core.recording;

import dev.voldechse.replayframework.adapter.CaptureContext;
import dev.voldechse.replayframework.adapter.CaptureSubject;
import dev.voldechse.replayframework.adapter.TrackRoute;
import dev.voldechse.replayframework.format.TrackKey;
import java.util.Optional;
import net.kyori.adventure.key.Key;

/**
 * Where a captured packet goes: the track its route names and the world a
 * playback client must be in to apply it. This is the one place that maps
 * a {@link TrackRoute} to a {@link TrackKey}; a new route is added here.
 */
final class TrackKeys {

    private TrackKeys() {
    }

    /**
     * The track of a packet.
     *
     * @param route the packet's route
     * @param frameWorld the packet's frame world, from {@link #frameWorld}
     */
    static Track of(TrackRoute route, Optional<String> frameWorld) {
        return switch (route) {
            case TrackRoute.Scene ignored -> scene(frameWorld);
            case TrackRoute.WorldWide ignored -> scene(frameWorld);
            case TrackRoute.BlockState ignored -> blockState(frameWorld.orElseThrow());
            case TrackRoute.Subject subject -> subject(subject.subject());
        };
    }

    /** The scene track of a frame world: the world's track, or the global track for none. */
    static Track scene(Optional<String> frameWorld) {
        return new Track(TrackKey.scene(frameWorld), frameWorld.orElse(""));
    }

    /** The state track of a subject. */
    static Track subject(CaptureSubject subject) {
        return new Track(TrackKey.state(subject.subject()), subject.label());
    }

    /** The block state track of a world. */
    static Track blockState(String world) {
        return new Track(TrackKey.blockState(world), world);
    }

    /**
     * The world a playback client must be in to apply a packet. Packets that
     * concern no place, such as chat or the tab list, apply in every world.
     * The state of a subject belongs to no world; the state of a block to its
     * world.
     */
    static Optional<String> frameWorld(CaptureContext context) {
        return switch (context.route()) {
            case TrackRoute.Scene ignored -> context.world().map(Key::asString);
            case TrackRoute.BlockState ignored -> context.world().map(Key::asString);
            case TrackRoute.WorldWide ignored -> Optional.empty();
            case TrackRoute.Subject ignored -> Optional.empty();
        };
    }

    /**
     * A track the recording writes to.
     *
     * @param key what identifies the track
     * @param label the display name it gets when it is created
     */
    record Track(TrackKey key, String label) {
    }
}
