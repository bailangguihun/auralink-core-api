package com.auralink.creation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;

import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.creation.provider.CreationProviderAdapter;
import com.auralink.creation.provider.ProviderAdapterRegistry;
import com.auralink.creation.provider.ProviderBinaryOutput;
import com.auralink.creation.provider.ProviderExecutionException;
import com.auralink.creation.provider.ProviderExecutionRequest;
import com.auralink.creation.provider.ProviderExecutionResult;
import com.auralink.creation.provider.ProviderOutput;
import com.auralink.creation.provider.ProviderTextOutput;
import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Serial executor target for one already-claimed Creation.  Its deliberately
 * transaction-free provider call is bracketed by short conditional state
 * changes in {@link CreationExecutionTransactionService}.
 */
@Component
@ConditionalOnProperty(prefix = "auralink.creations", name = "enabled", havingValue = "true")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CreationWorker {

    private final CreationExecutionTransactionService transactions;
    private final CreationInputMaterializer inputMaterializer;
    private final CreationResultPersistenceService results;
    private final CreationFeatureGuard featureGuard;
    private final CreationExecutionCapabilityService capabilityService;
    private final WorkflowCapabilityRegistry workflowCapabilities;
    private final ProviderAdapterRegistry adapters;
    private final PaintingPoemResultValidator poemValidator;
    private final ObjectMapper objectMapper;
    private final CreationLeaseHeartbeatService heartbeats;
    private final CreationRecoveryGate recoveryGate;
    private final CreationExecutionBoundaryHook boundaryHook;

    /** Compatibility constructor for existing focused worker tests. */
    public CreationWorker(
            CreationExecutionTransactionService transactions,
            CreationInputMaterializer inputMaterializer,
            CreationResultPersistenceService results,
            CreationFeatureGuard featureGuard,
            CreationExecutionCapabilityService capabilityService,
            WorkflowCapabilityRegistry workflowCapabilities,
            ProviderAdapterRegistry adapters,
            PaintingPoemResultValidator poemValidator,
            ObjectMapper objectMapper) {
        this(transactions, inputMaterializer, results, featureGuard, capabilityService, workflowCapabilities,
                adapters, poemValidator, objectMapper, null, null, NoOpCreationExecutionBoundaryHook.INSTANCE);
    }

    /** Compatibility constructor for focused heartbeat/ownership tests. */
    public CreationWorker(
            CreationExecutionTransactionService transactions,
            CreationInputMaterializer inputMaterializer,
            CreationResultPersistenceService results,
            CreationFeatureGuard featureGuard,
            CreationExecutionCapabilityService capabilityService,
            WorkflowCapabilityRegistry workflowCapabilities,
            ProviderAdapterRegistry adapters,
            PaintingPoemResultValidator poemValidator,
            ObjectMapper objectMapper,
            CreationLeaseHeartbeatService heartbeats,
            CreationRecoveryGate recoveryGate) {
        this(transactions, inputMaterializer, results, featureGuard, capabilityService, workflowCapabilities,
                adapters, poemValidator, objectMapper, heartbeats, recoveryGate,
                NoOpCreationExecutionBoundaryHook.INSTANCE);
    }

    public void execute(CreationExecutionTransactionService.ClaimedCreation claim) {
        if (recoveryGate != null && !recoveryGate.isOpen()) {
            return;
        }
        boundaryHook.reached(CreationExecutionBoundary.SUBMITTED_BEFORE_WORKER_RELOAD);
        CreationLeaseHeartbeatService.LeaseHeartbeatHandle heartbeat = heartbeats == null
                ? null : heartbeats.start(claim.id(), claim.claimToken());
        try {
            Optional<CreationExecutionTransactionService.ClaimedCreationData> loaded = transactions.loadClaimed(
                    claim.id(), claim.claimToken());
            if (loaded.isEmpty() || ownershipLost(heartbeat)) {
                return;
            }
            CreationExecutionTransactionService.ClaimedCreationData creation = loaded.get();
            final List<SnapshotStep> snapshotSteps;
            final List<CreationExecutionTransactionService.StepData> persistedSteps;
            try {
                snapshotSteps = parseAndValidateSnapshot(creation.workflowSnapshot());
                persistedSteps = transactions.loadSteps(creation.creationId());
                verifyStepMapping(snapshotSteps, persistedSteps);
                verifyRuntimeCapabilities(snapshotSteps);
            } catch (ApiV1Exception exception) {
                if (!ownershipLost(heartbeat)) {
                    transactions.failBeforeStep(creation.creationId(), creation.claimToken(),
                            exception.getCode() == com.auralink.api.v1.error.ApiErrorCode.CREATIONS_DISABLED
                                    ? CreationExecutionFailure.featureDisabled()
                                    : CreationExecutionFailure.operationUnavailable());
                }
                return;
            } catch (RuntimeException exception) {
                if (!ownershipLost(heartbeat)) {
                    transactions.failBeforeStep(creation.creationId(), creation.claimToken(),
                            isStepMismatch(exception)
                                    ? CreationExecutionFailure.stepMismatch()
                                    : CreationExecutionFailure.snapshotInvalid());
                }
                return;
            }

            boolean priorSucceeded = persistedSteps.stream()
                    .anyMatch(step -> CreationStepStatus.SUCCEEDED.name().equals(step.status()));
            for (int index = 0; index < persistedSteps.size(); index++) {
                if (ownershipLost(heartbeat) || !executionAllowed()) {
                    return;
                }
                CreationExecutionTransactionService.StepData step = persistedSteps.get(index);
                SnapshotStep snapshotStep = snapshotSteps.get(index);
                if (CreationStepStatus.SUCCEEDED.name().equals(step.status())) {
                    continue;
                }
                if (!CreationStepStatus.PENDING.name().equals(step.status())) {
                    return;
                }
                if (!transactions.startPendingStep(creation.creationId(), creation.claimToken(), step.stepId())) {
                    return;
                }
                boundaryHook.reached(CreationExecutionBoundary.STEP_RUNNING_BEFORE_SEND_STARTED);
                boundaryHook.reached(CreationExecutionBoundary.HARD_KILL_WINDOW);
                if (!executeStartedStep(
                        creation, step, snapshotStep, index == persistedSteps.size() - 1, priorSucceeded, heartbeat)) {
                        return;
                    }
                if (index < persistedSteps.size() - 1) {
                    boundaryHook.reached(CreationExecutionBoundary.BETWEEN_SUCCEEDED_STEPS);
                }
                priorSucceeded = true;
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.close();
            }
        }
    }

    private boolean executeStartedStep(
            CreationExecutionTransactionService.ClaimedCreationData creation,
            CreationExecutionTransactionService.StepData step,
            SnapshotStep snapshotStep,
            boolean terminal,
            boolean priorSucceeded,
            CreationLeaseHeartbeatService.LeaseHeartbeatHandle heartbeat) {
        CreationInputMaterializer.MaterializedInput input = null;
        ProviderArtifact resultArtifact = null;
        boolean providerReturned = false;
        boolean providerCallAdmitted = false;
        try {
            if (ownershipLost(heartbeat) || !executionAllowed()) {
                return false;
            }
            if (Thread.currentThread().isInterrupted()) {
                failStep(creation, step, priorSucceeded, CreationExecutionFailure.interrupted());
                return false;
            }
            input = inputMaterializer.materialize(creation, step);
            if (input.input() == null || input.input().modality() != snapshotStep.inputModality()) {
                throw new IllegalArgumentException("Materialized input modality does not match snapshot");
            }
            if (!executionAllowed() || ownershipLost(heartbeat)) {
                return false;
            }
            Optional<String> requestKey = transactions.markSendStarted(
                    creation.creationId(), creation.claimToken(), step.stepId());
            if (requestKey.isEmpty()) {
                return false;
            }
            boundaryHook.reached(CreationExecutionBoundary.SEND_STARTED_COMMITTED);
            if (ownershipLost(heartbeat) || !executionAllowed()) {
                return false;
            }
            if (Thread.currentThread().isInterrupted()) {
                failStep(creation, step, priorSucceeded, CreationExecutionFailure.interrupted());
                return false;
            }
            // This precedes gate admission so shutdown can prevent a new call.
            boundaryHook.reached(CreationExecutionBoundary.BEFORE_MOCK_ENTRY);
            if (recoveryGate != null && !recoveryGate.tryBeginProviderCall()) {
                return false;
            }
            providerCallAdmitted = recoveryGate != null;
            CreationProviderAdapter adapter = adapters.require(snapshotStep.operation(), snapshotStep.providerCode());
            // This call is intentionally outside a Spring transaction. Test adapters
            // assert TransactionSynchronizationManager.isActualTransactionActive() is false.
            ProviderExecutionResult result = adapter.execute(new ProviderExecutionRequest(
                    requestKey.get(), snapshotStep.operation(), snapshotStep.providerCode(), input.input()));
            providerReturned = true;
            resultArtifact = artifactOf(result == null ? null : result.output());
            boundaryHook.reached(CreationExecutionBoundary.MOCK_RETURNED_BEFORE_VALIDATION);
            if (ownershipLost(heartbeat)) {
                return false;
            }
            validateResult(result, requestKey.get(), snapshotStep);
            boundaryHook.reached(CreationExecutionBoundary.VALIDATED_BEFORE_MANAGED_PERSISTENCE);
            if (result.output() instanceof ProviderBinaryOutput binary) {
                results.persistPainting(creation, step, binary, terminal);
            } else if (result.output() instanceof ProviderTextOutput text) {
                results.persistPoem(creation, step, text, terminal);
            } else {
                throw new IllegalArgumentException("Provider output subtype is invalid");
            }
            boundaryHook.reached(CreationExecutionBoundary.RESULT_COMMITTED_BEFORE_ARTIFACT_CLOSE);
            return true;
        } catch (ClaimOwnershipLostException exception) {
            // A stale lease holder must discard the returned result rather than overwrite state.
            return false;
        } catch (ProviderExecutionException exception) {
            if (!ownershipLost(heartbeat)) {
                failStep(creation, step, priorSucceeded, CreationExecutionFailure.fromProvider(exception.category()));
            }
            return false;
        } catch (IllegalArgumentException exception) {
            // Materialization and result validation use this deliberately non-diagnostic signal.
            CreationExecutionFailure failure = providerReturned
                    ? CreationExecutionFailure.resultInvalid()
                    : CreationExecutionFailure.inputInvalid();
            if (!ownershipLost(heartbeat)) {
                failStep(creation, step, priorSucceeded, failure);
            }
            return false;
        } catch (RuntimeException exception) {
            if (!ownershipLost(heartbeat)) {
                failStep(creation, step, priorSucceeded, CreationExecutionFailure.persistenceFailed());
            }
            return false;
        } finally {
            if (providerCallAdmitted) {
                recoveryGate.finishProviderCall();
            }
            closeArtifact(resultArtifact);
            closeInput(input);
        }
    }

    private List<SnapshotStep> parseAndValidateSnapshot(String rawSnapshot) {
        try {
            WorkflowSnapshot snapshot = objectMapper.readValue(rawSnapshot, WorkflowSnapshot.class);
            if (snapshot == null || snapshot.snapshotVersion() != 1 || snapshot.graph() == null
                    || snapshot.graph().nodes() == null) {
                throw new IllegalArgumentException("Snapshot is invalid");
            }
            List<SnapshotStep> transforms = snapshot.graph().nodes().stream()
                    .filter(node -> node.kind() == WorkflowNodeKind.TRANSFORM)
                    .map(this::toSnapshotStep)
                    .toList();
            if (transforms.isEmpty()) {
                throw new IllegalArgumentException("Snapshot is invalid");
            }
            return transforms;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Snapshot is invalid", exception);
        }
    }

    private SnapshotStep toSnapshotStep(CanonicalWorkflowNode node) {
        if (node == null || node.id() == null || node.operation() == null || node.providerCode() == null
                || node.inputModality() == null || node.outputModality() == null) {
            throw new IllegalArgumentException("Snapshot is invalid");
        }
        return new SnapshotStep(
                node.id(), node.operation(), node.providerCode(), node.inputModality(), node.outputModality());
    }

    private void verifyStepMapping(
            List<SnapshotStep> snapshot,
            List<CreationExecutionTransactionService.StepData> persisted) {
        if (snapshot.size() != persisted.size()) {
            throw new StepMismatchException();
        }
        for (int index = 0; index < snapshot.size(); index++) {
            SnapshotStep expected = snapshot.get(index);
            CreationExecutionTransactionService.StepData actual = persisted.get(index);
            if (actual.stepIndex() != index
                    || !expected.nodeId().equals(actual.nodeId())
                    || !expected.operation().name().equals(actual.operationCode())
                    || !expected.providerCode().equals(actual.providerCode())
                    || !expected.inputModality().name().equals(actual.inputModality())
                    || !expected.outputModality().name().equals(actual.outputModality())) {
                throw new StepMismatchException();
            }
        }
    }

    private void verifyRuntimeCapabilities(List<SnapshotStep> snapshotSteps) {
        featureGuard.requireEnabled();
        for (SnapshotStep step : snapshotSteps) {
            WorkflowOperationCapability capability = workflowCapabilities.require(step.operation());
            if (!capability.definitionEnabled() || !capability.allowsProvider(step.providerCode())
                    || capability.inputModality() != step.inputModality()
                    || capability.outputModality() != step.outputModality()) {
                throw new StepMismatchException();
            }
            capabilityService.requireExecutionAvailable(step.operation(), step.providerCode());
        }
    }

    private void validateResult(
            ProviderExecutionResult result,
            String requestKey,
            SnapshotStep expected) {
        if (result == null || !requestKey.equals(result.requestId())
                || result.operation() != expected.operation()
                || !expected.providerCode().equals(result.providerCode())
                || result.outputModality() != expected.outputModality()) {
            throw new IllegalArgumentException("Provider result does not match snapshot");
        }
        if (expected.outputModality() == WorkflowModality.PAINTING) {
            if (!(result.output() instanceof ProviderBinaryOutput binary)
                    || binary.artifact() == null
                    || !("image/jpeg".equals(binary.mimeType()) || "image/png".equals(binary.mimeType()))
                    || !binary.mimeType().equals(binary.artifact().mimeType())
                    || binary.byteLength() != binary.artifact().byteLength()
                    || !binary.sha256().equals(binary.artifact().sha256())
                    || binary.width() == null || binary.height() == null) {
                throw new IllegalArgumentException("Painting output is invalid");
            }
            return;
        }
        if (expected.outputModality() == WorkflowModality.POEM
                && result.output() instanceof ProviderTextOutput text) {
            validatePoem(text);
            return;
        }
        throw new IllegalArgumentException("Provider output subtype is invalid");
    }

    private void validatePoem(ProviderTextOutput output) {
        try {
            LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
            raw.put("schemaVersion", output.schemaVersion());
            raw.put("title", output.title());
            raw.put("lines", output.lines());
            raw.put("text", output.text());
            poemValidator.validate(objectMapper.writeValueAsString(raw));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Poem output is invalid", exception);
        }
    }

    private ProviderArtifact artifactOf(ProviderOutput output) {
        return output instanceof ProviderBinaryOutput binary ? binary.artifact() : null;
    }

    private boolean isStepMismatch(RuntimeException exception) {
        return exception instanceof StepMismatchException;
    }

    private void failStep(
            CreationExecutionTransactionService.ClaimedCreationData creation,
            CreationExecutionTransactionService.StepData step,
            boolean priorSucceeded,
            CreationExecutionFailure failure) {
        try {
            transactions.failStep(
                    creation.creationId(), creation.claimToken(), step.stepId(), priorSucceeded, failure);
        } catch (ClaimOwnershipLostException ignored) {
            // Claim loss is deliberately silent: a stale worker may not alter newer state.
        }
    }

    private boolean ownershipLost(CreationLeaseHeartbeatService.LeaseHeartbeatHandle heartbeat) {
        return heartbeat != null && heartbeat.ownershipLost();
    }

    private boolean executionAllowed() {
        return recoveryGate == null || recoveryGate.isOpen();
    }

    private void closeArtifact(ProviderArtifact artifact) {
        if (artifact == null) {
            return;
        }
        try {
            artifact.close();
        } catch (RuntimeException ignored) {
            // ProviderArtifact itself enforces direct-child staging-root containment.
        } finally {
            boundaryHook.artifactCloseAttempted();
        }
    }

    private void closeInput(CreationInputMaterializer.MaterializedInput input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (RuntimeException ignored) {
            // Preserve the safe persisted outcome; cleanup remains staging-root constrained.
        }
    }

    private record SnapshotStep(
            String nodeId,
            WorkflowOperation operation,
            String providerCode,
            WorkflowModality inputModality,
            WorkflowModality outputModality) {
    }

    private static final class StepMismatchException extends RuntimeException {
    }
}
