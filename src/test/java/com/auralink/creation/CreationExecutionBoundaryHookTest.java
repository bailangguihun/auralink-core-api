package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class CreationExecutionBoundaryHookTest {

    @Test
    void normalHookIsSafeForEveryHarnessBoundaryAndHasNoActivationState() {
        for (CreationExecutionBoundary boundary : CreationExecutionBoundary.values()) {
            assertThatCode(() -> NoOpCreationExecutionBoundaryHook.INSTANCE.reached(boundary))
                    .doesNotThrowAnyException();
        }
        assertThatCode(NoOpCreationExecutionBoundaryHook.INSTANCE::artifactCloseAttempted)
                .doesNotThrowAnyException();
    }
}
