package com.auralink.creation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CreationStateMachineTest {

    private final CreationStateMachine stateMachine = new CreationStateMachine();

    @Test
    void permitsOnlyTheFrozenFoundationCreationTransitions() {
        assertDoesNotThrow(() -> stateMachine.requireInitial(CreationStatus.QUEUED));
        assertDoesNotThrow(() -> stateMachine.requireCreationTransition(
                CreationStatus.QUEUED, CreationStatus.RUNNING));
        for (CreationStatus terminal : new CreationStatus[] {
                CreationStatus.SUCCEEDED, CreationStatus.PARTIAL_SUCCESS, CreationStatus.FAILED}) {
            assertDoesNotThrow(() -> stateMachine.requireCreationTransition(CreationStatus.RUNNING, terminal));
            assertThrows(IllegalStateException.class,
                    () -> stateMachine.requireCreationTransition(terminal, CreationStatus.RUNNING));
        }
        assertDoesNotThrow(() -> stateMachine.requireRetryTransition(
                CreationStatus.FAILED, CreationStatus.QUEUED));
        assertDoesNotThrow(() -> stateMachine.requireRetryTransition(
                CreationStatus.PARTIAL_SUCCESS, CreationStatus.QUEUED));
        assertThrows(IllegalStateException.class,
                () -> stateMachine.requireRetryTransition(CreationStatus.SUCCEEDED, CreationStatus.QUEUED));
        assertThrows(IllegalStateException.class,
                () -> stateMachine.requireRetryTransition(CreationStatus.FAILED, CreationStatus.RUNNING));
        assertThrows(IllegalStateException.class,
                () -> stateMachine.requireCreationTransition(CreationStatus.QUEUED, CreationStatus.SUCCEEDED));
    }

    @Test
    void permitsOnlyTheFrozenFoundationStepTransitions() {
        assertDoesNotThrow(() -> stateMachine.requireStepTransition(
                CreationStepStatus.PENDING, CreationStepStatus.RUNNING));
        assertDoesNotThrow(() -> stateMachine.requireStepTransition(
                CreationStepStatus.PENDING, CreationStepStatus.SKIPPED));
        assertDoesNotThrow(() -> stateMachine.requireStepTransition(
                CreationStepStatus.RUNNING, CreationStepStatus.SUCCEEDED));
        assertDoesNotThrow(() -> stateMachine.requireStepTransition(
                CreationStepStatus.RUNNING, CreationStepStatus.FAILED));
        assertThrows(IllegalStateException.class,
                () -> stateMachine.requireStepTransition(CreationStepStatus.SUCCEEDED, CreationStepStatus.RUNNING));
        assertThrows(IllegalStateException.class,
                () -> stateMachine.requireStepTransition(CreationStepStatus.FAILED, CreationStepStatus.SUCCEEDED));
    }
}
