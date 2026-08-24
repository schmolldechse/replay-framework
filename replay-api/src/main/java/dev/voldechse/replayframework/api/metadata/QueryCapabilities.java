package dev.voldechse.replayframework.api.metadata;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable declaration of the query operations supported by a metadata key.
 */
public final class QueryCapabilities {
    /** Operations that a metadata key may expose to the query API. */
    public enum Operation {
        /** Exact value comparison. */
        EQUALITY,
        /** Ordered comparisons and sorting. */
        ORDERING,
        /** JSON-compatible containment comparison. */
        CONTAINMENT,
        /** Presence or absence comparison. */
        EXISTENCE
    }

    private final Set<Operation> operations;

    private QueryCapabilities(Set<Operation> operations) {
        this.operations = Set.copyOf(operations);
    }

    /**
     * Creates capabilities containing one operation.
     *
     * @param first first supported operation
     * @return immutable capabilities
     */
    public static QueryCapabilities of(Operation first) {
        return of(first, new Operation[0]);
    }

    /**
     * Creates capabilities containing the supplied operations.
     *
     * @param first first supported operation
     * @param additional remaining supported operations
     * @return immutable capabilities
     */
    public static QueryCapabilities of(Operation first, Operation[] additional) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(additional, "additional");
        EnumSet<Operation> values = EnumSet.of(first);
        for (Operation operation : additional) {
            values.add(Objects.requireNonNull(operation, "additional operation"));
        }
        return new QueryCapabilities(values);
    }

    /**
     * Creates a capability set that supports no metadata query operation.
     *
     * @return empty immutable capabilities
     */
    public static QueryCapabilities none() {
        return new QueryCapabilities(EnumSet.noneOf(Operation.class));
    }

    /**
     * Tests whether an operation is supported.
     *
     * @param operation operation to test
     * @return true when the operation is supported
     */
    public boolean supports(Operation operation) {
        return operations.contains(Objects.requireNonNull(operation, "operation"));
    }

    /**
     * Returns the immutable supported-operation set.
     *
     * @return immutable operations
     */
    public Set<Operation> operations() {
        return operations;
    }
}
