package dev.voldechse.replayframework.storage;

import dev.voldechse.replayframework.api.id.ReplayId;

/**
 * Opaque backend-owned handle for one open replay staging area.
 *
 * <p>The handle intentionally does not expose local paths, remote keys or credentials.</p>
 */
public interface StagingReplay {

    /**
     * Returns the replay identity represented by this staging handle.
     *
     * @return replay identity
     */
    ReplayId replayId();
}
