package com.auralink.creation;

/** Normal, non-configurable production implementation of the boundary seam. */
final class NoOpCreationExecutionBoundaryHook implements CreationExecutionBoundaryHook {

    static final NoOpCreationExecutionBoundaryHook INSTANCE = new NoOpCreationExecutionBoundaryHook();

    private NoOpCreationExecutionBoundaryHook() {
    }

    @Override
    public void reached(CreationExecutionBoundary boundary) {
        // Intentionally empty. Failure injection is not a production feature.
    }
}
