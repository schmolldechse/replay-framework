package dev.voldechse.replayframework.api.replay;

/**
 * Official artifact storage backends supported by the framework.
 */
public enum ReplayStorageBackend {
    /** Files on the configured local filesystem. */
    LOCAL,
    /** Objects in the configured S3-compatible bucket. */
    S3,
    /** Files on the configured verified SFTP server. */
    SFTP
}
