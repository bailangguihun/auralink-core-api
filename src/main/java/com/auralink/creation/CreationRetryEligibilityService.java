package com.auralink.creation;

import java.util.List;

import org.springframework.stereotype.Service;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.config.properties.CreationProviderProperties;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationStep;
import com.auralink.entity.MediaAsset;
import com.auralink.entity.Painting;
import com.auralink.media.MediaAssetValues;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/** Read-only, provider-free retry admission policy for terminal Creation rows. */
@Service
@RequiredArgsConstructor
public class CreationRetryEligibilityService {

    static final String NOT_AVAILABLE = "CREATION_RETRY_NOT_AVAILABLE";
    static final String AMBIGUOUS = "CREATION_RETRY_DISPATCH_AMBIGUOUS";
    static final String INCONSISTENT = "CREATION_DATA_INCONSISTENT";
    static final String FEATURE_DISABLED = "CREATIONS_FEATURE_DISABLED";

    private final CreationExecutionProperties executionProperties;
    private final CreationProviderProperties providerProperties;
    private final CreationExecutionCapabilityService capabilities;
    private final WorkflowCapabilityRegistry workflowCapabilities;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationStepDispatchAttemptRepository dispatchAttempts;
    private final PaintingPoemResultValidator poemValidator;
    private final MediaAssetStorageService mediaStorage;
    private final ObjectMapper objectMapper;

    public RetryAssessment assess(Creation creation, List<CreationStep> steps) {
        if (creation == null || steps == null) {
            return RetryAssessment.blocked(INCONSISTENT);
        }
        if (!CreationStatus.FAILED.name().equals(creation.getStatus())
                && !CreationStatus.PARTIAL_SUCCESS.name().equals(creation.getStatus())) {
            return RetryAssessment.blocked(NOT_AVAILABLE);
        }
        if (creation.getClaimToken() != null || creation.getLeaseExpiresAt() != null
                || executionAttempts.findByCreationIdAndFinishedAtIsNull(creation.getId()).isPresent()) {
            return RetryAssessment.blocked(INCONSISTENT);
        }
        try {
            List<SnapshotStep> snapshot = parseLinearSnapshot(creation.getWorkflowSnapshot());
            int boundary = requireConsistentSequence(creation, snapshot, steps);
            validateSource(creation, snapshot.get(0));
            for (int index = 0; index < boundary; index++) {
                validateSuccessfulOutput(creation, snapshot.get(index), steps.get(index));
            }
            CreationStep boundaryStep = steps.get(boundary);
            List<com.auralink.entity.CreationStepDispatchAttempt> history = dispatchAttempts
                    .findByCreationStepIdOrderByIdAsc(boundaryStep.getId());
            if ((ProviderDispatchState.NOT_SENT.name().equals(boundaryStep.getProviderDispatchState())
                    && boundaryStep.getProviderRequestKey() != null)
                    || history.stream().anyMatch(attempt -> ProviderDispatchState.NOT_SENT.name()
                    .equals(attempt.getDispatchState()) && attempt.getProviderRequestKey() != null)) {
                return RetryAssessment.blocked(INCONSISTENT);
            }
            if (history.stream().anyMatch(attempt -> ProviderDispatchState.RESULT_PERSISTED.name()
                    .equals(attempt.getDispatchState()))) {
                return RetryAssessment.blocked(INCONSISTENT);
            }
            if (history.stream().anyMatch(attempt -> ProviderDispatchState.SEND_STARTED.name()
                    .equals(attempt.getDispatchState()))
                    || ProviderDispatchState.SEND_STARTED.name().equals(boundaryStep.getProviderDispatchState())) {
                return RetryAssessment.blocked(AMBIGUOUS);
            }
            if (!executionProperties.isEnabled()) {
                return RetryAssessment.blocked(FEATURE_DISABLED);
            }
            for (SnapshotStep step : snapshot) {
                WorkflowOperationCapability capability = workflowCapabilities.require(step.operation());
                if (!capability.definitionEnabled()
                        || !capability.allowsProvider(step.providerCode())
                        || capability.inputModality() != step.input()
                        || capability.outputModality() != step.output()) {
                    return RetryAssessment.blocked("CREATION_OPERATION_UNAVAILABLE");
                }
                CreationExecutionCapabilityService.ExecutionAvailability availability = capabilities.availability(
                        step.operation(), step.providerCode());
                if (!availability.available()) {
                    return RetryAssessment.blocked(availability.reason());
                }
            }
            return RetryAssessment.available(boundary);
        } catch (RuntimeException exception) {
            return RetryAssessment.blocked(INCONSISTENT);
        }
    }

    private List<SnapshotStep> parseLinearSnapshot(String raw) {
        try {
            WorkflowSnapshot snapshot = objectMapper.readValue(raw, WorkflowSnapshot.class);
            if (snapshot == null || snapshot.snapshotVersion() != 1 || snapshot.graph() == null
                    || snapshot.graph().nodes() == null || snapshot.graph().edges() == null) {
                throw new RetryInconsistencyException();
            }
            List<CanonicalWorkflowNode> sources = snapshot.graph().nodes().stream()
                    .filter(node -> node.kind() == WorkflowNodeKind.SOURCE).toList();
            List<CanonicalWorkflowNode> transforms = snapshot.graph().nodes().stream()
                    .filter(node -> node.kind() == WorkflowNodeKind.TRANSFORM).toList();
            if (sources.size() != 1 || transforms.isEmpty()) {
                throw new RetryInconsistencyException();
            }
            String previous = sources.get(0).id();
            for (CanonicalWorkflowNode node : transforms) {
                String expectedPrevious = previous;
                if (node.id() == null || node.operation() == null || node.providerCode() == null
                        || node.inputModality() == null || node.outputModality() == null
                        || snapshot.graph().edges().stream().filter(edge -> expectedPrevious.equals(edge.from())
                                && node.id().equals(edge.to())).count() != 1) {
                    throw new RetryInconsistencyException();
                }
                previous = node.id();
            }
            if (snapshot.graph().edges().size() != transforms.size()) {
                throw new RetryInconsistencyException();
            }
            return transforms.stream().map(node -> new SnapshotStep(
                    node.id(), node.operation(), node.providerCode(), node.inputModality(), node.outputModality())).toList();
        } catch (RetryInconsistencyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RetryInconsistencyException();
        }
    }

    private int requireConsistentSequence(
            Creation creation,
            List<SnapshotStep> snapshot,
            List<CreationStep> steps) {
        if (steps.size() != snapshot.size()) {
            throw new RetryInconsistencyException();
        }
        int boundary = -1;
        boolean nonSuccessSeen = false;
        int failedCount = 0;
        int runningCount = 0;
        int succeededCount = 0;
        for (int index = 0; index < steps.size(); index++) {
            CreationStep step = steps.get(index);
            SnapshotStep expected = snapshot.get(index);
            if (step.getStepIndex() != index || !expected.nodeId().equals(step.getNodeId())
                    || expected.operation().name().equals(step.getOperationCode()) == false
                    || expected.providerCode().equals(step.getProviderCode()) == false
                    || expected.input().name().equals(step.getInputModality()) == false
                    || expected.output().name().equals(step.getOutputModality()) == false) {
                throw new RetryInconsistencyException();
            }
            String status = step.getStatus();
            if (CreationStepStatus.SUCCEEDED.name().equals(status)) {
                if (nonSuccessSeen) {
                    throw new RetryInconsistencyException();
                }
                succeededCount++;
                continue;
            }
            nonSuccessSeen = true;
            if (CreationStepStatus.FAILED.name().equals(status)) {
                failedCount++;
                if (boundary < 0) {
                    boundary = index;
                } else {
                    throw new RetryInconsistencyException();
                }
            } else if (CreationStepStatus.PENDING.name().equals(status)) {
                if (boundary < 0) {
                    boundary = index;
                }
                if (ProviderDispatchState.SEND_STARTED.name().equals(step.getProviderDispatchState())
                        || ProviderDispatchState.RESULT_PERSISTED.name().equals(step.getProviderDispatchState())
                        || step.getProviderRequestKey() != null) {
                    throw new RetryInconsistencyException();
                }
            } else if (CreationStepStatus.RUNNING.name().equals(status)) {
                runningCount++;
                throw new RetryInconsistencyException();
            } else {
                throw new RetryInconsistencyException();
            }
        }
        if (runningCount != 0 || boundary < 0) {
            throw new RetryInconsistencyException();
        }
        if (CreationStatus.PARTIAL_SUCCESS.name().equals(creation.getStatus())
                && (failedCount != 1 || succeededCount == 0)) {
            throw new RetryInconsistencyException();
        }
        if (CreationStatus.FAILED.name().equals(creation.getStatus())
                && (failedCount > 1 || succeededCount != 0)) {
            throw new RetryInconsistencyException();
        }
        CreationStep boundaryStep = steps.get(boundary);
        if (CreationStepStatus.FAILED.name().equals(boundaryStep.getStatus())
                && ProviderDispatchState.RESULT_PERSISTED.name().equals(boundaryStep.getProviderDispatchState())) {
            throw new RetryInconsistencyException();
        }
        return boundary;
    }

    private void validateSource(Creation creation, SnapshotStep first) {
        WorkflowModality source = parseModality(creation.getSourceModality());
        if (source != first.input()) {
            throw new RetryInconsistencyException();
        }
        switch (source) {
            case TEXT_DESCRIPTION, POEM -> {
                String text = creation.getSourceText();
                if (text == null || text.isBlank() || text.length() > providerProperties.getMaxTextChars()
                        || text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                                && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
                    throw new RetryInconsistencyException();
                }
            }
            case IMAGE -> requireOwnedImage(creation.getSourceAsset(), creation.getUser().getId(), false);
            case PAINTING -> {
                Painting painting = creation.getSourcePainting();
                if (painting == null || !"ACTIVE".equals(painting.getStatus()) || !painting.isImageAvailable()) {
                    throw new RetryInconsistencyException();
                }
                MediaAsset image = painting.getImageAsset();
                if (image == null || image.getOwnerUser() != null
                        || !MediaAssetValues.SourceType.CATALOG_REFERENCE.equals(image.getSourceType())
                        || !MediaAssetValues.Visibility.PUBLIC.equals(image.getVisibility())
                        || !MediaAssetValues.Status.ACTIVE.equals(image.getStatus())
                        || !MediaAssetValues.AssetType.IMAGE.equals(image.getAssetType())) {
                    throw new RetryInconsistencyException();
                }
                mediaStorage.resolve(image);
            }
            default -> throw new RetryInconsistencyException();
        }
    }

    private void validateSuccessfulOutput(Creation creation, SnapshotStep expected, CreationStep step) {
        if (!CreationStepStatus.SUCCEEDED.name().equals(step.getStatus())
                || !ProviderDispatchState.RESULT_PERSISTED.name().equals(step.getProviderDispatchState())) {
            throw new RetryInconsistencyException();
        }
        if (expected.output() == WorkflowModality.PAINTING) {
            requireOwnedImage(step.getOutputAsset(), creation.getUser().getId(), true);
            if (step.getOutputJson() != null) {
                throw new RetryInconsistencyException();
            }
            return;
        }
        if (expected.output() == WorkflowModality.POEM) {
            if (step.getOutputAsset() != null) {
                throw new RetryInconsistencyException();
            }
            poemValidator.validate(step.getOutputJson());
            return;
        }
        throw new RetryInconsistencyException();
    }

    private void requireOwnedImage(MediaAsset asset, Long ownerId, boolean generated) {
        if (asset == null || ownerId == null || asset.getOwnerUser() == null
                || !ownerId.equals(asset.getOwnerUser().getId())
                || !MediaAssetValues.Status.ACTIVE.equals(asset.getStatus())
                || !MediaAssetValues.AssetType.IMAGE.equals(asset.getAssetType())
                || !MediaAssetValues.Visibility.PRIVATE.equals(asset.getVisibility())
                || (generated && (!MediaAssetValues.SourceType.GENERATED.equals(asset.getSourceType())
                        || !MediaAssetValues.SemanticType.GENERATED_PAINTING.equals(asset.getSemanticType())))) {
            throw new RetryInconsistencyException();
        }
        mediaStorage.resolve(asset);
    }

    private WorkflowModality parseModality(String value) {
        try {
            return WorkflowModality.valueOf(value);
        } catch (RuntimeException exception) {
            throw new RetryInconsistencyException();
        }
    }

    public record RetryAssessment(boolean available, String blockedReason, int boundaryIndex) {
        static RetryAssessment available(int boundaryIndex) {
            return new RetryAssessment(true, null, boundaryIndex);
        }

        static RetryAssessment blocked(String reason) {
            return new RetryAssessment(false, reason, -1);
        }
    }

    private record SnapshotStep(
            String nodeId,
            com.auralink.workflow.WorkflowOperation operation,
            String providerCode,
            WorkflowModality input,
            WorkflowModality output) {
    }

    private static final class RetryInconsistencyException extends RuntimeException {
    }
}
