package dev.voldechse.replayframework.adapter;

import dev.voldechse.replayframework.adapter.playback.PlaybackIdentityContext;
import dev.voldechse.replayframework.adapter.playback.SceneQueries;
import dev.voldechse.replayframework.adapter.playback.StateDecoder;
import dev.voldechse.replayframework.adapter.playback.ViewerInteractions;
import dev.voldechse.replayframework.adapter.playback.ViewerStream;
import dev.voldechse.replayframework.adapter.playback.ViewerWorld;
import java.util.function.Consumer;

/**
 * Adapter boundary for one prepared viewer. Each role is its own interface,
 * so the core depends only on the role it uses and a new feature adds a role
 * instead of growing this one:
 *
 * <ul>
 *   <li>{@link ViewerStream}: frames, view reset and the outbound queue;</li>
 *   <li>{@link ViewerWorld}: the recorded world the viewer watches;</li>
 *   <li>{@link SceneQueries}: entities, players and observer anchors;</li>
 *   <li>{@link StateDecoder}: subject and block state;</li>
 *   <li>{@link ViewerInteractions}: the viewer's clicks on the replay.</li>
 * </ul>
 */
public interface PlaybackBridge extends ViewerStream, ViewerWorld, SceneQueries, StateDecoder, ViewerInteractions {

    /** Binds the bridge to one deterministic, session-local identity map. */
    void setIdentityContext(PlaybackIdentityContext context);

    /** Routes asynchronous adapter failures back into the owning playback session. */
    void setFailureHandler(Consumer<Throwable> failureHandler);

    /** Closes the bridge and removes bridge-owned handlers. Repeated calls are safe. */
    void close();
}
