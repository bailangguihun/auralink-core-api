package com.auralink.workflow.snapshot;

/** Snapshot value plus its canonical deterministic JSON representation. */
public record WorkflowSnapshotResult(WorkflowSnapshot snapshot, String canonicalJson) {
}
