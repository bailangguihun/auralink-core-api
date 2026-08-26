package com.auralink.creation;

import java.util.List;

import org.springframework.stereotype.Component;

import com.auralink.api.v1.creation.CreationDetailResponse;
import com.auralink.api.v1.creation.CreationPoemResponse;
import com.auralink.api.v1.creation.CreationStepSummaryResponse;
import com.auralink.api.v1.creation.CreationSummaryResponse;
import com.auralink.api.v1.creation.CreationTimestampFormatter;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationStep;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auralink.provider.qwen.PaintingPoemResult;
import com.auralink.provider.qwen.PaintingPoemResultValidator;

/** Maps only explicit safe Creation response fields; internal snapshots and JSON are never returned. */
@Component
public class CreationResponseMapper {

    private final ObjectMapper objectMapper;
    private final PaintingPoemResultValidator poemValidator;
    private final CreationStepRepository steps;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationRetryEligibilityService retryEligibility;

    public CreationResponseMapper(
            ObjectMapper objectMapper,
            PaintingPoemResultValidator poemValidator,
            CreationStepRepository steps,
            CreationExecutionAttemptRepository executionAttempts,
            CreationRetryEligibilityService retryEligibility) {
        this.objectMapper = objectMapper;
        this.poemValidator = poemValidator;
        this.steps = steps;
        this.executionAttempts = executionAttempts;
        this.retryEligibility = retryEligibility;
    }

    public CreationSummaryResponse summary(Creation creation) {
        return summary(creation, steps.findByCreationIdOrderByStepIndexAsc(creation.getId()));
    }

    private CreationSummaryResponse summary(Creation creation, List<CreationStep> creationSteps) {
        CreationRetryEligibilityService.RetryAssessment retry = retryEligibility.assess(creation, creationSteps);
        return new CreationSummaryResponse(
                creation.getPublicId(),
                creation.getWorkflow() == null ? null : creation.getWorkflow().getPublicId(),
                workflowName(creation),
                CreationStatus.valueOf(creation.getStatus()),
                creation.getErrorCode(),
                creation.getErrorMessage(),
                recoveryState(creation.getErrorCode()),
                com.auralink.workflow.WorkflowModality.valueOf(creation.getSourceModality()),
                creation.getSourcePainting() == null ? null : creation.getSourcePainting().getPublicId(),
                creation.getSourceAsset() == null ? null : creation.getSourceAsset().getPublicId(),
                creation.getFinalModality() == null
                        ? null : com.auralink.workflow.WorkflowModality.valueOf(creation.getFinalModality()),
                creation.getFinalAsset() == null ? null : creation.getFinalAsset().getPublicId(),
                contentUrl(creation.getFinalAsset()),
                downloadUrl(creation.getFinalAsset()),
                CreationTimestampFormatter.format(creation.getCreatedAt()),
                CreationTimestampFormatter.format(creation.getUpdatedAt()),
                CreationTimestampFormatter.format(creation.getStartedAt()),
                CreationTimestampFormatter.format(creation.getFinishedAt()),
                creation.getRetryVersion(),
                retry.available(),
                retry.blockedReason(),
                executionAttempts.countByCreationId(creation.getId()));
    }

    public CreationDetailResponse detail(Creation creation, List<CreationStep> steps) {
        CreationSummaryResponse summary = summary(creation, steps);
        CreationPoemResponse poem = finalPoem(creation);
        return new CreationDetailResponse(
                summary.creationId(),
                summary.workflowId(),
                summary.workflowName(),
                summary.status(),
                summary.errorCode(),
                summary.errorMessage(),
                summary.recoveryState(),
                summary.sourceModality(),
                summary.sourcePaintingId(),
                summary.sourceAssetId(),
                summary.finalModality(),
                summary.finalAssetId(),
                summary.finalAssetContentUrl(),
                summary.finalAssetDownloadUrl(),
                poem == null ? null : poem.text(),
                poem,
                summary.createdAt(),
                summary.updatedAt(),
                summary.startedAt(),
                summary.finishedAt(),
                summary.retryVersion(),
                summary.retryAvailable(),
                summary.retryBlockedReason(),
                summary.executionAttemptCount(),
                steps.stream().map(this::step).toList());
    }

    private CreationStepSummaryResponse step(CreationStep step) {
        return new CreationStepSummaryResponse(
                step.getPublicId(),
                step.getStepIndex(),
                com.auralink.workflow.WorkflowOperation.valueOf(step.getOperationCode()),
                com.auralink.workflow.WorkflowModality.valueOf(step.getInputModality()),
                com.auralink.workflow.WorkflowModality.valueOf(step.getOutputModality()),
                CreationStepStatus.valueOf(step.getStatus()),
                step.getAttemptCount(),
                step.getErrorCode(),
                step.getErrorMessage(),
                step.getOutputAsset() == null ? null : step.getOutputAsset().getPublicId(),
                CreationTimestampFormatter.format(step.getStartedAt()),
                CreationTimestampFormatter.format(step.getFinishedAt()));
    }

    private String workflowName(Creation creation) {
        try {
            return objectMapper.readValue(creation.getWorkflowSnapshot(), WorkflowSnapshot.class).workflowName();
        } catch (Exception exception) {
            return null;
        }
    }

    private CreationPoemResponse finalPoem(Creation creation) {
        if (creation.getFinalOutputJson() == null
                || !com.auralink.workflow.WorkflowModality.POEM.name().equals(creation.getFinalModality())) {
            return null;
        }
        try {
            PaintingPoemResult poem = poemValidator.validate(creation.getFinalOutputJson());
            return new CreationPoemResponse(poem.schemaVersion(), poem.title(), poem.lines(), poem.text());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String contentUrl(com.auralink.entity.MediaAsset asset) {
        return asset == null ? null : "/api/v1/assets/" + asset.getPublicId() + "/content";
    }

    private String downloadUrl(com.auralink.entity.MediaAsset asset) {
        return asset == null ? null : "/api/v1/assets/" + asset.getPublicId() + "/download";
    }

    private CreationRecoveryState recoveryState(String errorCode) {
        if (CreationRecoveryStateInspector.AMBIGUOUS.equals(errorCode)) {
            return CreationRecoveryState.PROVIDER_DISPATCH_AMBIGUOUS;
        }
        if (CreationRecoveryStateInspector.INCONSISTENT.equals(errorCode)
                || CreationRecoveryStateInspector.RESULT_INCONSISTENT.equals(errorCode)) {
            return CreationRecoveryState.OPERATOR_REVIEW_REQUIRED;
        }
        return CreationRecoveryState.NONE;
    }
}
