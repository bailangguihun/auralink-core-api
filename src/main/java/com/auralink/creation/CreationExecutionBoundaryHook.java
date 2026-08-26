package com.auralink.creation;

/**
 * Narrow internal seam for the disposable failure-injection harness.
 * Implementations must never include Creation, Provider, or filesystem data
 * in boundary events.
 */
public interface CreationExecutionBoundaryHook {

    void reached(CreationExecutionBoundary boundary);

    /** Called after an output artifact close has been attempted. */
    default void artifactCloseAttempted() {
        // Normal execution deliberately has no observation side effect.
    }
}
