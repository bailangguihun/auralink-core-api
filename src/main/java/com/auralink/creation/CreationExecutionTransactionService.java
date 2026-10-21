package com.auralink.creation;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
import com.auralink.repository.CreationStepRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreationExecutionTransactionService {
   private final CreationRepository creations;
   private final CreationStepRepository steps;
   private final CreationExecutionAttemptRepository executionAttempts;
   private final CreationStepDispatchAttemptRepository dispatchAttempts;
   private final CreationExecutionProperties properties;
   private final Clock clock;

   public CreationExecutionTransactionService(
      CreationRepository creations,
      CreationStepRepository steps,
      CreationExecutionAttemptRepository executionAttempts,
      CreationStepDispatchAttemptRepository dispatchAttempts,
      CreationExecutionProperties properties
   ) {
      this(creations, steps, executionAttempts, dispatchAttempts, properties, Clock.systemUTC());
   }

   @Transactional
   public Optional<CreationExecutionTransactionService.ClaimedCreation> claimOldestQueued() {
      Creation candidate = this.creations.findFirstByStatusOrderByCreatedAtAscIdAsc(CreationStatus.QUEUED.name()).orElse(null);
      if (candidate == null) {
         return Optional.empty();
      }

      LocalDateTime now = LocalDateTime.now(this.clock);
      String token = UUID.randomUUID().toString();
      int claimed = this.creations.claimQueued(candidate.getId(), token, now.plus(this.properties.getLeaseDuration()), now);
      return claimed == 1 ? Optional.of(new CreationExecutionTransactionService.ClaimedCreation(candidate.getId(), token)) : Optional.empty();
   }

   @Transactional(readOnly = true)
   public Optional<CreationExecutionTransactionService.ClaimedCreationData> loadClaimed(Long creationId, String claimToken) {
      return creationId != null && claimToken != null
         ? this.creations.findByIdAndStatusAndClaimToken(creationId, CreationStatus.RUNNING.name(), claimToken).map(this::toClaimedData)
         : Optional.empty();
   }

   @Transactional(readOnly = true)
   public List<CreationExecutionTransactionService.StepData> loadSteps(Long creationId) {
      return this.steps.findByCreationIdOrderByStepIndexAsc(creationId).stream().map(this::toStepData).toList();
   }

   @Transactional
   public boolean startPendingStep(Long creationId, String claimToken, Long stepId) {
      LocalDateTime now = LocalDateTime.now(this.clock);
      int changed = this.steps.startPending(stepId, creationId, claimToken, now);
      if (changed != 1) {
         return false;
      }

      CreationExecutionAttempt executionAttempt = this.requireActiveAttempt(creationId);
      CreationStep step = (CreationStep)this.steps.findById(stepId).orElseThrow(ClaimOwnershipLostException::new);
      CreationStepDispatchAttempt dispatchAttempt = this.dispatchAttempts
         .findByCreationStepIdAndCreationExecutionAttemptId(stepId, executionAttempt.getId())
         .orElse(null);
      if (dispatchAttempt == null) {
         this.dispatchAttempts
            .save(
               CreationStepDispatchAttempt.builder()
                  .creationStep(step)
                  .creationExecutionAttempt(executionAttempt)
                  .dispatchState(ProviderDispatchState.NOT_SENT.name())
                  .build()
            );
      } else if (!ProviderDispatchState.NOT_SENT.name().equals(dispatchAttempt.getDispatchState()) || dispatchAttempt.getProviderRequestKey() != null) {
         throw new ClaimOwnershipLostException();
      }

      this.requireLeaseRefresh(creationId, claimToken, now);
      return true;
   }

   @Transactional
   public Optional<String> markSendStarted(Long creationId, String claimToken, Long stepId) {
      String requestKey = UUID.randomUUID().toString();
      CreationExecutionAttempt executionAttempt = this.requireActiveAttempt(creationId);
      LocalDateTime now = LocalDateTime.now(this.clock);
      int changed = this.steps.markSendStarted(stepId, creationId, claimToken, requestKey);
      if (changed != 1) {
         return Optional.empty();
      }

      if (this.dispatchAttempts.markSendStarted(stepId, executionAttempt.getId(), creationId, claimToken, requestKey, now) != 1) {
         throw new ClaimOwnershipLostException();
      }

      this.requireLeaseRefresh(creationId, claimToken, now);
      return Optional.of(requestKey);
   }

   @Transactional
   public boolean returnRejectedSubmissionToQueue(Long creationId, String claimToken) {
      return this.creations.releaseRejectedBeforeDispatch(creationId, claimToken, LocalDateTime.now(this.clock)) == 1;
   }

   @Transactional
   public boolean failStep(Long creationId, String claimToken, Long stepId, boolean priorStepSucceeded, CreationExecutionFailure failure) {
      LocalDateTime now = LocalDateTime.now(this.clock);
      int changed = this.steps.failRunning(stepId, creationId, claimToken, failure.code(), failure.message(), now);
      if (changed != 1) {
         return false;
      }

      CreationExecutionAttempt executionAttempt = this.requireActiveAttempt(creationId);
      if (this.dispatchAttempts.finishFailure(stepId, executionAttempt.getId(), creationId, claimToken, failure.code(), now) != 1) {
         throw new ClaimOwnershipLostException();
      }

      CreationStatus terminal = priorStepSucceeded ? CreationStatus.PARTIAL_SUCCESS : CreationStatus.FAILED;
      if (this.creations.failClaimed(creationId, claimToken, terminal.name(), failure.code(), failure.message(), now) != 1) {
         throw new ClaimOwnershipLostException();
      }

      this.finishExecutionAttempt(executionAttempt, terminal.name(), now);
      return true;
   }

   @Transactional
   public boolean failBeforeStep(Long creationId, String claimToken, CreationExecutionFailure failure) {
      LocalDateTime now = LocalDateTime.now(this.clock);
      if (this.creations.failClaimed(creationId, claimToken, CreationStatus.FAILED.name(), failure.code(), failure.message(), now) != 1) {
         return false;
      }

      this.finishExecutionAttempt(this.requireActiveAttempt(creationId), CreationStatus.FAILED.name(), now);
      return true;
   }

   private CreationExecutionAttempt requireActiveAttempt(Long creationId) {
      return this.executionAttempts.findByCreationIdAndFinishedAtIsNull(creationId).orElseThrow(ClaimOwnershipLostException::new);
   }

   private void finishExecutionAttempt(CreationExecutionAttempt executionAttempt, String resolutionCode, LocalDateTime now) {
      executionAttempt.setFinishedAt(now);
      executionAttempt.setResolutionCode(resolutionCode);
      this.executionAttempts.save(executionAttempt);
   }

   private void requireLeaseRefresh(Long creationId, String claimToken, LocalDateTime now) {
      if (this.creations.refreshLease(creationId, claimToken, now.plus(this.properties.getLeaseDuration()), now) != 1) {
         throw new ClaimOwnershipLostException();
      }
   }

   private CreationExecutionTransactionService.ClaimedCreationData toClaimedData(Creation creation) {
      return new CreationExecutionTransactionService.ClaimedCreationData(
         creation.getId(),
         creation.getClaimToken(),
         creation.getUser().getId(),
         creation.getWorkflowSnapshot(),
         creation.getSourceModality(),
         creation.getSourceText(),
         creation.getSourceAsset() == null ? null : creation.getSourceAsset().getId(),
         creation.getSourcePainting() == null ? null : creation.getSourcePainting().getId(),
         creation.getRetryVersion()
      );
   }

   private CreationExecutionTransactionService.StepData toStepData(CreationStep step) {
      return new CreationExecutionTransactionService.StepData(
         step.getId(),
         step.getStepIndex(),
         step.getNodeId(),
         step.getOperationCode(),
         step.getProviderCode(),
         step.getInputModality(),
         step.getOutputModality(),
         step.getStatus(),
         step.getAttemptCount(),
         step.getProviderDispatchState()
      );
   }

   @Autowired
   public CreationExecutionTransactionService(
      final CreationRepository creations,
      final CreationStepRepository steps,
      final CreationExecutionAttemptRepository executionAttempts,
      final CreationStepDispatchAttemptRepository dispatchAttempts,
      final CreationExecutionProperties properties,
      final Clock clock
   ) {
      this.creations = creations;
      this.steps = steps;
      this.executionAttempts = executionAttempts;
      this.dispatchAttempts = dispatchAttempts;
      this.properties = properties;
      this.clock = clock;
   }

   public record ClaimedCreation(Long id, String claimToken) {
   }

   public record ClaimedCreationData(
      Long creationId,
      String claimToken,
      Long ownerId,
      String workflowSnapshot,
      String sourceModality,
      String sourceText,
      Long sourceAssetId,
      Long sourcePaintingId,
      int retryVersion
   ) {
      public ClaimedCreationData(Long creationId, String claimToken, Long ownerId, String workflowSnapshot,
                                 String sourceModality, String sourceText, Long sourceAssetId, Long sourcePaintingId) {
         this(creationId, claimToken, ownerId, workflowSnapshot, sourceModality, sourceText, sourceAssetId, sourcePaintingId, 0);
      }
   }

   public record StepData(
      Long stepId,
      int stepIndex,
      String nodeId,
      String operationCode,
      String providerCode,
      String inputModality,
      String outputModality,
      String status,
      int attemptCount,
      String dispatchState
   ) {
   }
}
