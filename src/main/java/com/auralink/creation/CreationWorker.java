package com.auralink.creation;

import com.auralink.api.v1.error.ApiErrorCode;
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
import com.auralink.provider.vmm.VmmExecutionContext;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "auralink.creations", name = "enabled", havingValue = "true")
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

   public CreationWorker(
      CreationExecutionTransactionService transactions,
      CreationInputMaterializer inputMaterializer,
      CreationResultPersistenceService results,
      CreationFeatureGuard featureGuard,
      CreationExecutionCapabilityService capabilityService,
      WorkflowCapabilityRegistry workflowCapabilities,
      ProviderAdapterRegistry adapters,
      PaintingPoemResultValidator poemValidator,
      ObjectMapper objectMapper
   ) {
      this(
         transactions,
         inputMaterializer,
         results,
         featureGuard,
         capabilityService,
         workflowCapabilities,
         adapters,
         poemValidator,
         objectMapper,
         null,
         null,
         NoOpCreationExecutionBoundaryHook.INSTANCE
      );
   }

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
      CreationRecoveryGate recoveryGate
   ) {
      this(
         transactions,
         inputMaterializer,
         results,
         featureGuard,
         capabilityService,
         workflowCapabilities,
         adapters,
         poemValidator,
         objectMapper,
         heartbeats,
         recoveryGate,
         NoOpCreationExecutionBoundaryHook.INSTANCE
      );
   }

   public void execute(CreationExecutionTransactionService.ClaimedCreation claim) {
      if (this.recoveryGate == null || this.recoveryGate.isOpen()) {
         this.boundaryHook.reached(CreationExecutionBoundary.SUBMITTED_BEFORE_WORKER_RELOAD);
         CreationLeaseHeartbeatService.LeaseHeartbeatHandle heartbeat = this.heartbeats == null ? null : this.heartbeats.start(claim.id(), claim.claimToken());

         try {
            Optional<CreationExecutionTransactionService.ClaimedCreationData> loaded = this.transactions.loadClaimed(claim.id(), claim.claimToken());
            if (!loaded.isEmpty() && !this.ownershipLost(heartbeat)) {
               CreationExecutionTransactionService.ClaimedCreationData creation = loaded.get();

               List<CreationWorker.SnapshotStep> snapshotSteps;
               List<CreationExecutionTransactionService.StepData> persistedSteps;
               try {
                  snapshotSteps = this.parseAndValidateSnapshot(creation.workflowSnapshot());
                  persistedSteps = this.transactions.loadSteps(creation.creationId());
                  this.verifyStepMapping(snapshotSteps, persistedSteps);
                  this.verifyRuntimeCapabilities(snapshotSteps, creation);
               } catch (ApiV1Exception exception) {
                  if (!this.ownershipLost(heartbeat)) {
                     this.transactions
                        .failBeforeStep(
                           creation.creationId(),
                           creation.claimToken(),
                           exception.getCode() == ApiErrorCode.CREATIONS_DISABLED
                              ? CreationExecutionFailure.featureDisabled()
                              : CreationExecutionFailure.operationUnavailable()
                        );
                  }

                  return;
               } catch (RuntimeException exception) {
                  if (!this.ownershipLost(heartbeat)) {
                     this.transactions
                        .failBeforeStep(
                           creation.creationId(),
                           creation.claimToken(),
                           this.isStepMismatch(exception) ? CreationExecutionFailure.stepMismatch() : CreationExecutionFailure.snapshotInvalid()
                        );
                  }

                  return;
               }

               boolean priorSucceeded = persistedSteps.stream().anyMatch(stepx -> CreationStepStatus.SUCCEEDED.name().equals(stepx.status()));

               for (int index = 0; index < persistedSteps.size(); index++) {
                  if (this.ownershipLost(heartbeat) || !this.executionAllowed()) {
                     return;
                  }

                  CreationExecutionTransactionService.StepData step = persistedSteps.get(index);
                  CreationWorker.SnapshotStep snapshotStep = snapshotSteps.get(index);
                  if (!CreationStepStatus.SUCCEEDED.name().equals(step.status())) {
                     if (!CreationStepStatus.PENDING.name().equals(step.status())) {
                        return;
                     }

                     if (!this.transactions.startPendingStep(creation.creationId(), creation.claimToken(), step.stepId())) {
                        return;
                     }

                     this.boundaryHook.reached(CreationExecutionBoundary.STEP_RUNNING_BEFORE_SEND_STARTED);
                     this.boundaryHook.reached(CreationExecutionBoundary.HARD_KILL_WINDOW);
                     if (!this.executeStartedStep(creation, step, snapshotStep, index == persistedSteps.size() - 1, priorSucceeded, heartbeat)) {
                        return;
                     }

                     if (index < persistedSteps.size() - 1) {
                        this.boundaryHook.reached(CreationExecutionBoundary.BETWEEN_SUCCEEDED_STEPS);
                     }

                     priorSucceeded = true;
                  }
               }
            }
         } finally {
            if (heartbeat != null) {
               heartbeat.close();
            }
         }
      }
   }

   private boolean executeStartedStep(
      CreationExecutionTransactionService.ClaimedCreationData creation,
      CreationExecutionTransactionService.StepData step,
      CreationWorker.SnapshotStep snapshotStep,
      boolean terminal,
      boolean priorSucceeded,
      CreationLeaseHeartbeatService.LeaseHeartbeatHandle heartbeat
   ) {
      CreationInputMaterializer.MaterializedInput input = null;
      ProviderArtifact resultArtifact = null;
      boolean providerReturned = false;
      boolean providerCallAdmitted = false;

      try {
         if (this.ownershipLost(heartbeat) || !this.executionAllowed()) {
            return false;
         }

         if (Thread.currentThread().isInterrupted()) {
            this.failStep(creation, step, priorSucceeded, CreationExecutionFailure.interrupted());
            return false;
         }

         input = this.inputMaterializer.materialize(creation, step);
         if (input.input() == null || input.input().modality() != snapshotStep.inputModality()) {
            throw new IllegalArgumentException("Materialized input modality does not match snapshot");
         }

         if (this.executionAllowed() && !this.ownershipLost(heartbeat)) {
            Optional<String> requestKey = this.transactions.markSendStarted(creation.creationId(), creation.claimToken(), step.stepId());
            if (requestKey.isEmpty()) {
               return false;
            }

            this.boundaryHook.reached(CreationExecutionBoundary.SEND_STARTED_COMMITTED);
            if (this.ownershipLost(heartbeat) || !this.executionAllowed()) {
               return false;
            }

            if (Thread.currentThread().isInterrupted()) {
               this.failStep(creation, step, priorSucceeded, CreationExecutionFailure.interrupted());
               return false;
            }

            this.boundaryHook.reached(CreationExecutionBoundary.BEFORE_MOCK_ENTRY);
            if (this.recoveryGate != null && !this.recoveryGate.tryBeginProviderCall()) {
               return false;
            }

            providerCallAdmitted = this.recoveryGate != null;
            CreationProviderAdapter adapter = this.adapters.require(snapshotStep.operation(), snapshotStep.providerCode());
            ProviderExecutionResult result = adapter.execute(
               new ProviderExecutionRequest(requestKey.get(), snapshotStep.operation(), snapshotStep.providerCode(), input.input(), snapshotStep.parameters(),
                  snapshotStep.operation() == WorkflowOperation.PAINTING_TO_MUSIC
                     ? new VmmExecutionContext(creation.ownerId(), creation.creationId(), step.stepId(), creation.retryVersion(), creation.sourcePaintingId())
                     : null)
            );
            providerReturned = true;
            resultArtifact = this.artifactOf(result == null ? null : result.output());
            this.boundaryHook.reached(CreationExecutionBoundary.MOCK_RETURNED_BEFORE_VALIDATION);
            if (this.ownershipLost(heartbeat)) {
               return false;
            }

            this.validateResult(result, requestKey.get(), snapshotStep);
            this.boundaryHook.reached(CreationExecutionBoundary.VALIDATED_BEFORE_MANAGED_PERSISTENCE);
            if (snapshotStep.outputModality() == WorkflowModality.PAINTING && result.output() instanceof ProviderBinaryOutput binary) {
               this.results.persistPainting(creation, step, binary, terminal);
            } else if (snapshotStep.outputModality() == WorkflowModality.AUDIO && result.output() instanceof ProviderBinaryOutput binary) {
               this.results.persistMusic(creation, step, binary, this.musicDuration(snapshotStep), terminal);
            } else {
               if (!(result.output() instanceof ProviderTextOutput text)) {
                  throw new IllegalArgumentException("Provider output subtype is invalid");
               }

               this.results.persistPoem(creation, step, text, terminal);
            }

            this.boundaryHook.reached(CreationExecutionBoundary.RESULT_COMMITTED_BEFORE_ARTIFACT_CLOSE);
            return true;
         } else {
            return false;
         }
      } catch (ClaimOwnershipLostException exception) {
         return false;
      } catch (ProviderExecutionException exception) {
         if (!this.ownershipLost(heartbeat)) {
            this.failStep(creation, step, priorSucceeded, CreationExecutionFailure.fromProvider(exception.category()));
         }

         return false;
      } catch (IllegalArgumentException exception) {
         CreationExecutionFailure failure = providerReturned ? CreationExecutionFailure.resultInvalid() : CreationExecutionFailure.inputInvalid();
         if (!this.ownershipLost(heartbeat)) {
            this.failStep(creation, step, priorSucceeded, failure);
         }

         return false;
      } catch (RuntimeException exception) {
         if (!this.ownershipLost(heartbeat)) {
            this.failStep(creation, step, priorSucceeded, CreationExecutionFailure.persistenceFailed());
         }

         return false;
      } finally {
         if (providerCallAdmitted) {
            this.recoveryGate.finishProviderCall();
         }

         this.closeArtifact(resultArtifact);
         this.closeInput(input);
      }
   }

   private List<CreationWorker.SnapshotStep> parseAndValidateSnapshot(String rawSnapshot) {
      try {
         WorkflowSnapshot snapshot = (WorkflowSnapshot)this.objectMapper.readValue(rawSnapshot, WorkflowSnapshot.class);
         if (snapshot != null && snapshot.snapshotVersion() == 1 && snapshot.graph() != null && snapshot.graph().nodes() != null) {
            List<CreationWorker.SnapshotStep> transforms = snapshot.graph()
               .nodes()
               .stream()
               .filter(node -> node.kind() == WorkflowNodeKind.TRANSFORM)
               .map(this::toSnapshotStep)
               .toList();
            if (transforms.isEmpty()) {
               throw new IllegalArgumentException("Snapshot is invalid");
            } else {
               return transforms;
            }
         } else {
            throw new IllegalArgumentException("Snapshot is invalid");
         }
      } catch (Exception exception) {
         throw new IllegalArgumentException("Snapshot is invalid", exception);
      }
   }

   private CreationWorker.SnapshotStep toSnapshotStep(CanonicalWorkflowNode node) {
      if (node != null
         && node.id() != null
         && node.operation() != null
         && node.providerCode() != null
         && node.inputModality() != null
         && node.outputModality() != null) {
         return new CreationWorker.SnapshotStep(
            node.id(), node.operation(), node.providerCode(), node.inputModality(), node.outputModality(), node.parameters()
         );
      } else {
         throw new IllegalArgumentException("Snapshot is invalid");
      }
   }

   private void verifyStepMapping(List<CreationWorker.SnapshotStep> snapshot, List<CreationExecutionTransactionService.StepData> persisted) {
      if (snapshot.size() != persisted.size()) {
         throw new CreationWorker.StepMismatchException();
      }

      for (int index = 0; index < snapshot.size(); index++) {
         CreationWorker.SnapshotStep expected = snapshot.get(index);
         CreationExecutionTransactionService.StepData actual = persisted.get(index);
         if (actual.stepIndex() != index
            || !expected.nodeId().equals(actual.nodeId())
            || !expected.operation().name().equals(actual.operationCode())
            || !expected.providerCode().equals(actual.providerCode())
            || !expected.inputModality().name().equals(actual.inputModality())
            || !expected.outputModality().name().equals(actual.outputModality())) {
            throw new CreationWorker.StepMismatchException();
         }
      }
   }

   private void verifyRuntimeCapabilities(List<CreationWorker.SnapshotStep> snapshotSteps, CreationExecutionTransactionService.ClaimedCreationData creation) {
      this.featureGuard.requireEnabled();
      this.capabilityService.requireExecutionTransformCount(snapshotSteps.size());
      this.capabilityService
         .requireExecutionAvailable(
            snapshotSteps.stream().map(CreationWorker.SnapshotStep::operation).toList(),
            creation.sourceModality(),
            creation.sourcePaintingId(),
            creation.sourceAssetId()
         );

      for (CreationWorker.SnapshotStep step : snapshotSteps) {
         WorkflowOperationCapability capability = this.workflowCapabilities.require(step.operation());
         if (!capability.definitionEnabled()
            || !capability.allowsProvider(step.providerCode())
            || capability.inputModality() != step.inputModality()
            || capability.outputModality() != step.outputModality()) {
            throw new CreationWorker.StepMismatchException();
         }

         this.capabilityService.requireExecutionAvailable(step.operation(), step.providerCode());
      }
   }

   private void validateResult(ProviderExecutionResult result, String requestKey, CreationWorker.SnapshotStep expected) {
      if (result == null
         || !requestKey.equals(result.requestId())
         || result.operation() != expected.operation()
         || !expected.providerCode().equals(result.providerCode())
         || result.outputModality() != expected.outputModality()) {
         throw new IllegalArgumentException("Provider result does not match snapshot");
      }

      if (expected.outputModality() == WorkflowModality.PAINTING) {
         if (!(
            result.output() instanceof ProviderBinaryOutput binary
               && binary.artifact() != null
               && ("image/jpeg".equals(binary.mimeType()) || "image/png".equals(binary.mimeType()))
               && binary.mimeType().equals(binary.artifact().mimeType())
               && binary.byteLength() == binary.artifact().byteLength()
               && binary.sha256().equals(binary.artifact().sha256())
               && binary.width() != null
               && binary.height() != null
         )) {
            throw new IllegalArgumentException("Painting output is invalid");
         }
      } else if (expected.outputModality() == WorkflowModality.AUDIO) {
         if (result.output() instanceof ProviderBinaryOutput binary
            && binary.artifact() != null
            && "audio/wav".equals(binary.mimeType())
            && binary.mimeType().equals(binary.artifact().mimeType())
            && binary.byteLength() == binary.artifact().byteLength()
            && binary.sha256().equals(binary.artifact().sha256())
            && binary.width() == null
            && binary.height() == null) {
            this.musicDuration(expected);
         } else {
            throw new IllegalArgumentException("Music output is invalid");
         }
      } else if (expected.outputModality() == WorkflowModality.POEM && result.output() instanceof ProviderTextOutput text) {
         this.validatePoem(text);
      } else {
         throw new IllegalArgumentException("Provider output subtype is invalid");
      }
   }

   private void validatePoem(ProviderTextOutput output) {
      try {
         LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
         raw.put("schemaVersion", output.schemaVersion());
         raw.put("title", output.title());
         raw.put("lines", output.lines());
         raw.put("text", output.text());
         this.poemValidator.validate(this.objectMapper.writeValueAsString(raw));
      } catch (Exception exception) {
         throw new IllegalArgumentException("Poem output is invalid", exception);
      }
   }

   private ProviderArtifact artifactOf(ProviderOutput output) {
      return output instanceof ProviderBinaryOutput binary ? binary.artifact() : null;
   }

   private int musicDuration(CreationWorker.SnapshotStep step) {
      if (step.operation() != WorkflowOperation.PAINTING_TO_MUSIC) {
         throw new IllegalArgumentException("Music result does not match its operation");
      } else {
         JsonNode duration = step.parameters().get("durationSeconds");
         if (duration != null && duration.isIntegralNumber() && duration.canConvertToInt() && duration.intValue() >= 3 && duration.intValue() <= 30) {
            return duration.intValue();
         } else {
            throw new IllegalArgumentException("Music duration is invalid");
         }
      }
   }

   private boolean isStepMismatch(RuntimeException exception) {
      return exception instanceof CreationWorker.StepMismatchException;
   }

   private void failStep(
      CreationExecutionTransactionService.ClaimedCreationData creation,
      CreationExecutionTransactionService.StepData step,
      boolean priorSucceeded,
      CreationExecutionFailure failure
   ) {
      try {
         this.transactions.failStep(creation.creationId(), creation.claimToken(), step.stepId(), priorSucceeded, failure);
      } catch (ClaimOwnershipLostException var6) {
      }
   }

   private boolean ownershipLost(CreationLeaseHeartbeatService.LeaseHeartbeatHandle heartbeat) {
      return heartbeat != null && heartbeat.ownershipLost();
   }

   private boolean executionAllowed() {
      return this.recoveryGate == null || this.recoveryGate.isOpen();
   }

   private void closeArtifact(ProviderArtifact artifact) {
      if (artifact != null) {
         try {
            artifact.close();
         } catch (RuntimeException var6) {
         } finally {
            this.boundaryHook.artifactCloseAttempted();
         }
      }
   }

   private void closeInput(CreationInputMaterializer.MaterializedInput input) {
      if (input != null) {
         try {
            input.close();
         } catch (RuntimeException var3) {
         }
      }
   }

   @Autowired
   public CreationWorker(
      final CreationExecutionTransactionService transactions,
      final CreationInputMaterializer inputMaterializer,
      final CreationResultPersistenceService results,
      final CreationFeatureGuard featureGuard,
      final CreationExecutionCapabilityService capabilityService,
      final WorkflowCapabilityRegistry workflowCapabilities,
      final ProviderAdapterRegistry adapters,
      final PaintingPoemResultValidator poemValidator,
      final ObjectMapper objectMapper,
      final CreationLeaseHeartbeatService heartbeats,
      final CreationRecoveryGate recoveryGate,
      final CreationExecutionBoundaryHook boundaryHook
   ) {
      this.transactions = transactions;
      this.inputMaterializer = inputMaterializer;
      this.results = results;
      this.featureGuard = featureGuard;
      this.capabilityService = capabilityService;
      this.workflowCapabilities = workflowCapabilities;
      this.adapters = adapters;
      this.poemValidator = poemValidator;
      this.objectMapper = objectMapper;
      this.heartbeats = heartbeats;
      this.recoveryGate = recoveryGate;
      this.boundaryHook = boundaryHook;
   }

   private record SnapshotStep(
      String nodeId,
      WorkflowOperation operation,
      String providerCode,
      WorkflowModality inputModality,
      WorkflowModality outputModality,
      Map<String, JsonNode> parameters
   ) {
      private SnapshotStep {
         parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
      }
   }

   private static final class StepMismatchException extends RuntimeException {
   }
}
