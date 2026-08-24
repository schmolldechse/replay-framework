package dev.voldechse.replayframework.api.replay;

import dev.voldechse.replayframework.api.id.ReplayId;
import dev.voldechse.replayframework.api.metadata.ReplayMetadata;
import dev.voldechse.replayframework.api.query.ReplayPage;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous public facade for replay catalog operations.
 */
public interface ReplayService {
    /**
     * Finds one replay by identifier.
     *
     * @param replayId replay identifier
     * @return stage completed with the replay metadata when present
     */
    CompletionStage<Optional<ReplayMetadata>> find(ReplayId replayId);

    /**
     * Executes a PostgreSQL-independent typed query.
     *
     * @param query immutable query AST
     * @return stage completed with one result page
     */
    CompletionStage<ReplayPage<ReplayMetadata>> query(ReplayQuery query);

    /**
     * Deletes a replay and its artifacts asynchronously.
     *
     * @param replayId replay identifier
     * @return stage completed after deletion
     */
    CompletionStage<Void> delete(ReplayId replayId);

}
