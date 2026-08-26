package com.auralink.api.v1.workflow;

import com.auralink.workflow.WorkflowModality;

/** Stable workflow list item; the full graph is intentionally omitted. */
public record WorkflowSummaryResponse(
        String workflowId,
        String name,
        String description,
        int schemaVersion,
        WorkflowModality sourceModality,
        WorkflowModality terminalModality,
        int nodeCount,
        String updatedAt,
        String createdAt) {
}
