package dev.voldechse.replayframework.adapter.playback;

import dev.voldechse.replayframework.api.playback.RecordedSubject;
import dev.voldechse.replayframework.api.playback.SubjectState;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import dev.voldechse.replayframework.format.RawPacketFrame;
import java.util.List;
import java.util.Optional;

/** Builds recorded state, such as an inventory or a chest's contents, from the frames of a state track. */
public interface StateDecoder {

    /**
     * The state of a subject from the frames of its state track: the
     * checkpoint at the playback position's barrier, then the frames up to
     * the position.
     */
    Optional<SubjectState> decodeState(RecordedSubject subject, List<RawPacketFrame> frames);

    /**
     * The state of a block from the frames of its world's block state track:
     * the checkpoint at the playback position's barrier, then the frames up to
     * the position.
     */
    Optional<SubjectState> decodeBlock(BlockPosition position, List<RawPacketFrame> frames);
}
