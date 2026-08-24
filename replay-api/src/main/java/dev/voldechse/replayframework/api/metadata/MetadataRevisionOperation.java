package dev.voldechse.replayframework.api.metadata;

/**
 * Native-persistence-compatible operation recorded for a metadata revision.
 */
public enum MetadataRevisionOperation {
    /** One or more values or fixed fields were set. */
    SET,
    /** One or more custom values were removed. */
    REMOVE,
    /** The complete metadata snapshot was replaced. */
    REPLACE
}
