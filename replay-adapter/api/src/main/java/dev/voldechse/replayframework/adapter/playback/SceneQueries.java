package dev.voldechse.replayframework.adapter.playback;

import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.Optional;

/** What the replay shows at the playback position, as the frames played so far describe it. */
public interface SceneQueries {

    /**
     * Decodes ordered observer anchors from an initial checkpoint. Native
     * packet interpretation remains owned by the versioned adapter.
     */
    List<ObserverAnchor> observerAnchors(List<RawPacketFrame> checkpointFrames);

    /** What the scene shows of the entity the viewer's client knows by this ID. */
    Optional<RecordedEntity> entity(int clientEntityId);

    /** Every entity of the viewed world at the current position. */
    List<RecordedEntity> entities();

    /** Every known player name, and the location of the players in the viewed world. */
    List<RecordedPlayer> recordedPlayers();

    /**
     * The players found in frames of one world that are not played, such as a
     * world's checkpoint and the frames up to the playback position. Used to
     * locate players in worlds the viewer does not watch.
     */
    List<RecordedPlayer> players(List<RawPacketFrame> frames);
}
