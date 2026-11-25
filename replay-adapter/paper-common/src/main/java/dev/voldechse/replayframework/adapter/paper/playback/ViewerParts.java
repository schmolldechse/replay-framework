package dev.voldechse.replayframework.adapter.paper.playback;

import dev.voldechse.replayframework.adapter.PacketDescriptor;
import dev.voldechse.replayframework.adapter.PlaybackBridge;
import dev.voldechse.replayframework.adapter.codec.PacketCodec;
import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.api.playback.RecordedEntity;
import dev.voldechse.replayframework.api.recording.BlockPosition;
import io.netty.channel.Channel;
import java.util.Optional;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The version-specific parts of one viewer's playback, created by
 * {@link PlaybackHooks#open}. {@link PaperPlaybackBridge} drives them.
 */
public interface ViewerParts {

    /** Decodes and encodes packets with the viewer connection's live codec. */
    PacketCodec<Object> codec();

    /** The PLAY clientbound descriptor of a live packet object, empty for anything else. */
    Optional<PacketDescriptor> classify(Object livePacket);

    /** Creates the rewriter of the playback session's identity space. */
    ViewerRewriter rewriter(PlaybackIdentityContext context, int observerEntityId);

    /** The resetter of the viewer's client. */
    ViewResetter resetter();

    /** What the playback knows about the scene. */
    ViewerScene scene();

    /**
     * Adds the handler that reads the viewer's clicks on replay entities and
     * recorded containers. Called on the connection's event loop.
     */
    void installInteractions(Channel channel, Interactions interactions);

    /** Removes the handler {@link #installInteractions} added. Called on the connection's event loop. */
    void uninstallInteractions(Channel channel);

    /** What the bridge offers the version's parts. */
    interface Callbacks {

        /** Writes a native packet to the viewer, such as one that removes a replay entity. */
        void write(Object packet);

        /** Fails the playback. */
        void fail(Throwable failure);
    }

    /** What the interaction handler asks of the playback. */
    interface Interactions {

        /** What the scene shows of the entity the viewer's client knows by this ID. */
        Optional<RecordedEntity> entity(int clientEntityId);

        /** Receives a click on a replay entity. */
        void entityInteracted(PlaybackBridge.EntityInteraction interaction);

        /** The click on a recorded container at a position, when there is one. */
        Optional<PlaybackBridge.BlockInteraction> block(BlockPosition position, EquipmentSlot hand);

        /** Receives a click on a recorded container. */
        void blockInteracted(PlaybackBridge.BlockInteraction interaction);

        /** Reports a failure; the packet passes on. */
        void fail(Throwable failure);
    }
}
