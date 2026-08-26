package com.auralink.creation;

/** Stable persisted state vocabulary for a Creation. */
public enum CreationStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL_SUCCESS,
    FAILED
}
