package com.auralink.api.v1.creation;

import com.auralink.creation.CreationStepStatus;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/** Safe public step projection. Dispatch markers, JSON payloads, and provider data stay internal. */
public record CreationStepSummaryResponse(
        String stepId,
        int stepIndex,
        WorkflowOperation operation,
        WorkflowModality inputModality,
        WorkflowModality outputModality,
        CreationStepStatus status,
        int attemptCount,
        String errorCode,
        String errorMessage,
        String outputAssetId,
        String startedAt,
        String finishedAt) {
}
