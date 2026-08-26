package com.auralink.api.v1.creation;

import java.util.List;

import com.auralink.creation.CreationStatus;
import com.auralink.creation.CreationRecoveryState;
import com.auralink.workflow.WorkflowModality;

/** Owner-only Creation detail projection. */
public record CreationDetailResponse(
        String creationId,
        String workflowId,
        String workflowName,
        CreationStatus status,
        String errorCode,
        String errorMessage,
        CreationRecoveryState recoveryState,
        WorkflowModality sourceModality,
        String sourcePaintingId,
        String sourceAssetId,
        WorkflowModality finalModality,
        String finalAssetId,
        String finalAssetContentUrl,
        String finalAssetDownloadUrl,
        String finalText,
        CreationPoemResponse finalPoem,
        String createdAt,
        String updatedAt,
        String startedAt,
        String finishedAt,
        int retryVersion,
        boolean retryAvailable,
        String retryBlockedReason,
        long executionAttemptCount,
        List<CreationStepSummaryResponse> steps) {

    public CreationDetailResponse {
        steps = List.copyOf(steps);
    }
}
