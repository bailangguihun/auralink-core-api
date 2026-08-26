package com.auralink.creation;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;

import lombok.RequiredArgsConstructor;

/**
 * Every persisted queue mutation is a short conditional transaction.  The
 * worker deliberately calls this service before and after, never around, a
 * provider invocation.
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CreationExecutionTransactionService {

    private final CreationRepository creations;
    private final CreationStepRepository steps;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationStepDispatchAttemptRepository dispatchAttempts;
    private final CreationExecutionProperties properties;
    private final Clock clock;

    /** Compatibility constructor for focused legacy unit tests; production injects the UTC bean. */
    public CreationExecutionTransactionService(
            CreationRepository creations,
            CreationStepRepository steps,
            CreationExecutionAttemptRepository executionAttempts,
            CreationStepDispatchAttemptRepository dispatchAttempts,
            CreationExecutionProperties properties) {
        this(creations, steps, executionAttempts, dispatchAttempts, properties, Clock.systemUTC());
    }

    @Transactional
    public Optional<ClaimedCreation> claimOldestQueued() {
        Creation candidate = creations.findFirstByStatusOrderByCreatedAtAscIdAsc(CreationStatus.QUEUED.name())
                .orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String token = UUID.randomUUID().toString();
        int claimed = creations.claimQueued(
                candidate.getId(), token, now.plus(properties.getLeaseDuration()), now);
        return claimed == 1
                ? Optional.of(new ClaimedCreation(candidate.getId(), token))
                : Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<ClaimedCreationData> loadClaimed(Long creationId, String claimToken) {
        if (creationId == null || claimToken == null) {
            return Optional.empty();
        }
        return creations.findByIdAndStatusAndClaimToken(
                        creationId, CreationStatus.RUNNING.name(), claimToken)
                .map(this::toClaimedData);
    }

    @Transactional(readOnly = true)
    public List<StepData> loadSteps(Long creationId) {
        return steps.findByCreationIdOrderByStepIndexAsc(creationId).stream()
                .map(this::toStepData)
                .toList();
    }

    @Transactional
    public boolean startPendingStep(Long creationId, String claimToken, Long stepId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int changed = steps.startPending(stepId, creationId, claimToken, now);
        if (changed != 1) {
            return false;
        }
        CreationExecutionAttempt executionAttempt = requireActiveAttempt(creationId);
        CreationStep step = steps.findById(stepId).orElseThrow(ClaimOwnershipLostException::new);
        CreationStepDispatchAttempt dispatchAttempt = dispatchAttempts
                .findByCreationStepIdAndCreationExecutionAttemptId(stepId, executionAttempt.getId())
                .orElse(null);
        if (dispatchAttempt == null) {
            dispatchAttempts.save(CreationStepDispatchAttempt.builder()
                    .creationStep(step)
                    .creationExecutionAttempt(executionAttempt)
                    .dispatchState(ProviderDispatchState.NOT_SENT.name())
                    .build());
        } else if (!ProviderDispatchState.NOT_SENT.name().equals(dispatchAttempt.getDispatchState())
                || dispatchAttempt.getProviderRequestKey() != null) {
            throw new ClaimOwnershipLostException();
        }
        requireLeaseRefresh(creationId, claimToken, now);
        return true;
    }

    @Transactional
    public Optional<String> markSendStarted(Long creationId, String claimToken, Long stepId) {
        String requestKey = UUID.randomUUID().toString();
        CreationExecutionAttempt executionAttempt = requireActiveAttempt(creationId);
        LocalDateTime now = LocalDateTime.now(clock);
        int changed = steps.markSendStarted(stepId, creationId, claimToken, requestKey);
        if (changed != 1) {
            return Optional.empty();
        }
        if (dispatchAttempts.markSendStarted(
                stepId, executionAttempt.getId(), creationId, claimToken, requestKey, now) != 1) {
            throw new ClaimOwnershipLostException();
        }
        requireLeaseRefresh(creationId, claimToken, now);
        return Optional.of(requestKey);
    }

    @Transactional
    public boolean returnRejectedSubmissionToQueue(Long creationId, String claimToken) {
        return creations.releaseRejectedBeforeDispatch(creationId, claimToken, LocalDateTime.now(clock)) == 1;
    }

    @Transactional
    public boolean failStep(
            Long creationId,
            String claimToken,
            Long stepId,
            boolean priorStepSucceeded,
            CreationExecutionFailure failure) {
        LocalDateTime now = LocalDateTime.now(clock);
        int changed = steps.failRunning(
                stepId, creationId, claimToken, failure.code(), failure.message(), now);
        if (changed != 1) {
            return false;
        }
        CreationExecutionAttempt executionAttempt = requireActiveAttempt(creationId);
        if (dispatchAttempts.finishFailure(
                stepId, executionAttempt.getId(), creationId, claimToken, failure.code(), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
        CreationStatus terminal = priorStepSucceeded
                ? CreationStatus.PARTIAL_SUCCESS : CreationStatus.FAILED;
        if (creations.failClaimed(
                creationId, claimToken, terminal.name(), failure.code(), failure.message(), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
        finishExecutionAttempt(executionAttempt, terminal.name(), now);
        return true;
    }

    @Transactional
    public boolean failBeforeStep(
            Long creationId,
            String claimToken,
            CreationExecutionFailure failure) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (creations.failClaimed(
                creationId, claimToken, CreationStatus.FAILED.name(),
                failure.code(), failure.message(), now) != 1) {
            return false;
        }
        finishExecutionAttempt(requireActiveAttempt(creationId), CreationStatus.FAILED.name(), now);
        return true;
    }

    private CreationExecutionAttempt requireActiveAttempt(Long creationId) {
        return executionAttempts.findByCreationIdAndFinishedAtIsNull(creationId)
                .orElseThrow(ClaimOwnershipLostException::new);
    }

    private void finishExecutionAttempt(
            CreationExecutionAttempt executionAttempt,
            String resolutionCode,
            LocalDateTime now) {
        executionAttempt.setFinishedAt(now);
        executionAttempt.setResolutionCode(resolutionCode);
        executionAttempts.save(executionAttempt);
    }

    private void requireLeaseRefresh(Long creationId, String claimToken, LocalDateTime now) {
        if (creations.refreshLease(
                creationId, claimToken, now.plus(properties.getLeaseDuration()), now) != 1) {
            throw new ClaimOwnershipLostException();
        }
    }

    private ClaimedCreationData toClaimedData(Creation creation) {
        return new ClaimedCreationData(
                creation.getId(),
                creation.getClaimToken(),
                creation.getUser().getId(),
                creation.getWorkflowSnapshot(),
                creation.getSourceModality(),
                creation.getSourceText(),
                creation.getSourceAsset() == null ? null : creation.getSourceAsset().getId(),
                creation.getSourcePainting() == null ? null : creation.getSourcePainting().getId());
    }

    private StepData toStepData(CreationStep step) {
        return new StepData(
                step.getId(), step.getStepIndex(), step.getNodeId(), step.getOperationCode(),
                step.getProviderCode(), step.getInputModality(), step.getOutputModality(),
                step.getStatus(), step.getAttemptCount(), step.getProviderDispatchState());
    }

    public record ClaimedCreation(Long id, String claimToken) {
    }

    /** Detached execution data; no entity or mutable UserWorkflow is exposed to the worker. */
    public record ClaimedCreationData(
            Long creationId,
            String claimToken,
            Long ownerId,
            String workflowSnapshot,
            String sourceModality,
            String sourceText,
            Long sourceAssetId,
            Long sourcePaintingId) {
    }

    /** Persisted step view intentionally excludes JSON, request key and raw output. */
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
            String dispatchState) {
    }
}
