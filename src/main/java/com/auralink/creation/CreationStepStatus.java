package com.auralink.creation;

/** Stable persisted state vocabulary for an individual Creation transform. */
public enum CreationStepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
