package dev.voldechse.replayframework.adapter.paper.playback;

import dev.voldechse.replayframework.adapter.playback.ObserverAnchor;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.playback.RecordedPlayer;
import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.key.Key;

/**
 * What a viewer's playback knows about the scene, kept from the frames it
 * plays: players, entities and containers, and the decoding of state tracks.
 * Implemented once per version, because it reads Minecraft packets.
 */
public interface ViewerScene {

    /** Notes one frame the playback processes, of any world. */
    void observe(RawPacketFrame frame, String typeName);

    /** Forgets everything; the frames after a view reset rebuild it. */
    void clear();

    /** Every player the frames played so far know, with the viewed world's locations. */
    List<RecordedPlayer> players();

    /** The players found in frames that are not played, such as another world's. */
    List<RecordedPlayer> players(List<RawPacketFrame> frames);

    /** What the scene shows of the entity with a recorded entity ID. */
    Optional<RecordedEntity> entity(int recordedEntityId);

    /** Every entity of a world. */
    List<RecordedEntity> entities(String world);

    /** The type of the container block at a position of a world, when the scene shows one. */
    Optional<Key> container(String world, BlockPosition position);

    /** Builds the state of a subject from its state track's frames. */
    Optional<SubjectState> decodeState(RecordedSubject subject, List<RawPacketFrame> frames);

    /** Builds the contents of a container from its world's block state track. */
    Optional<SubjectState> decodeBlock(BlockPosition position, List<RawPacketFrame> frames);

    /** Where a viewer can be placed: the players and entities of a checkpoint. */
    List<ObserverAnchor> observerAnchors(List<RawPacketFrame> checkpointFrames);
}
