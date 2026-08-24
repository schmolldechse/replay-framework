package dev.voldechse.replayframework.storage;

import dev.voldechse.replayframework.api.id.ReplayId;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous internal port for staging, publishing and reading replay artifacts.
 *
 * <p>This contract is not part of the public replay-api facade. Implementations must keep
 * backend paths and credentials behind this boundary.</p>
 */
public interface ReplayStorage {

    /**
     * Creates one open staging area for a replay.
     *
     * @param replayId replay identity to stage
     * @return asynchronous opaque staging handle
     */
    CompletionStage<StagingReplay> stage(ReplayId replayId);

    /**
     * Copies one complete local source file into an open staging area.
     *
     * @param staging open staging handle
     * @param key destination artifact key
     * @param source complete local source file
     * @return asynchronous completion of the immutable artifact put
     */
    CompletionStage<Void> put(StagingReplay staging, ArtifactKey key, Path source);

    /**
     * Publishes the complete staged replay after its manifest commit marker exists.
     *
     * @param staging open staging handle
     * @return asynchronous completion of the atomic publish
     */
    CompletionStage<Void> publish(StagingReplay staging);

    /**
     * Reads a published artifact fully or over an optional half-open byte range.
     *
     * @param replayId published replay identity
     * @param key source artifact key
     * @param range optional range; empty requests the complete artifact
     * @param target local destination path outside the backend root
     * @return asynchronous path of the atomically completed target
     */
    CompletionStage<Path> fetch(
            ReplayId replayId,
            ArtifactKey key,
            Optional<ByteRange> range,
            Path target);

    /**
     * Tests whether a regular artifact exists in the published namespace.
     *
     * @param replayId published replay identity
     * @param key artifact key
     * @return asynchronous existence result
     */
    CompletionStage<Boolean> exists(ReplayId replayId, ArtifactKey key);

    /**
     * Deletes published and staging data for one replay identity idempotently.
     *
     * @param replayId replay identity to remove
     * @return asynchronous completion of the bounded delete
     */
    CompletionStage<Void> delete(ReplayId replayId);
}
