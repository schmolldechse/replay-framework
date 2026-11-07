package dev.voldechse.replayframework.adapter;

/** Selected adapter and its version-specific resources. */
public interface PaperAdapterHandle extends AutoCloseable {

    ReplayAdapter adapter();

    /** Closes owned resources; repeated calls must be safe. */
    @Override
    void close();
}
