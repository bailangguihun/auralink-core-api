package com.auralink.creation;

/**
 * Internal execution boundaries used only by the disposable ROUND 9C-C harness.
 * The normal application receives a no-op hook and has no configuration switch
 * capable of enabling a non-no-op implementation.
 */
public enum CreationExecutionBoundary {
    CLAIM_COMMITTED_BEFORE_SUBMIT,
    SUBMITTED_BEFORE_WORKER_RELOAD,
    STEP_RUNNING_BEFORE_SEND_STARTED,
    SEND_STARTED_COMMITTED,
    BEFORE_MOCK_ENTRY,
    MOCK_DURING_EXECUTION,
    MOCK_RETURNED_BEFORE_VALIDATION,
    VALIDATED_BEFORE_MANAGED_PERSISTENCE,
    MANAGED_FILE_BEFORE_DB_COMMIT,
    RESULT_COMMITTED_BEFORE_ARTIFACT_CLOSE,
    BETWEEN_SUCCEEDED_STEPS,
    BEFORE_TERMINAL_CREATION_MUTATION,
    STARTUP_RECOVERY_GATE_CLOSED,
    SCHEDULED_RECOVERY_SWEEP,
    GRACEFUL_SHUTDOWN_DURING_AWAIT,
    HARD_KILL_WINDOW
}
