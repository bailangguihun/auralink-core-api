package com.auralink.creation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.auralink.config.properties.CreationExecutionProperties;
import com.auralink.entity.Creation;
import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.repository.CreationExecutionAttemptRepository;
import com.auralink.repository.CreationRepository;
import com.auralink.repository.CreationStepDispatchAttemptRepository;
import com.auralink.repository.CreationStepRepository;

/** Short fenced database transitions for automatic recovery; never calls a provider. */
@Service
public class CreationRecoveryTransactionService {

    private final CreationRepository creations;
    private final CreationStepRepository steps;
    private final CreationExecutionAttemptRepository executionAttempts;
    private final CreationStepDispatchAttemptRepository dispatchAttempts;
    private final CreationExecutionProperties properties;
    private final CreationRecoveryStateInspector inspector;
    private final Clock clock;

    public CreationRecoveryTransactionService(
            CreationRepository creations,
            CreationStepRepository steps,
            CreationExecutionAttemptRepository executionAttempts,
            CreationStepDispatchAttemptRepository dispatchAttempts,
            CreationExecutionProperties properties,
            CreationRecoveryStateInspector inspector,
            Clock clock) {
        this.creations = creations;
        this.steps = steps;
        this.executionAttempts = executionAttempts;
        this.dispatchAttempts = dispatchAttempts;
        this.properties = properties;
        this.inspector = inspector;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RecoveryCandidate> candidates() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(properties.getRecoveryGrace());
        return creations.findExpiredRecoveryCandidates(cutoff, properties.getRecoveryBatchSize()).stream()
                .map(creation -> new RecoveryCandidate(
                        creation.getId(), creation.getClaimToken(), creation.getLeaseExpiresAt()))
                .toList();
    }

    @Transactional
    public Optional<RecoveryFence> fence(RecoveryCandidate candidate) {
        if (candidate == null || candidate.claimToken() == null || candidate.leaseExpiresAt() == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minus(properties.getRecoveryGrace());
        String token = UUID.randomUUID().toString();
        int changed = creations.fenceExpiredClaim(
                candidate.creationId(), candidate.claimToken(), candidate.leaseExpiresAt(), cutoff,
                token, now.plus(properties.getRecoveryFenceLease()), now);
        return changed == 1 ? Optional.of(new RecoveryFence(candidate.creationId(), token)) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<FencedInspection> inspect(RecoveryFence fence) {
        Optional<Creation> creation = creations.findByIdAndStatusAndClaimToken(
                fence.creationId(), CreationStatus.RUNNING.name(), fence.recoveryToken());
        if (creation.isEmpty()) {
            return Optional.empty();
        }
        List<CreationStep> creationSteps = steps.findByCreationIdOrderByStepIndexAsc(fence.creationId());
        List<CreationExecutionAttempt> active = executionAttempts
                .findByCreationIdAndFinishedAtIsNullOrderByIdAsc(fence.creationId());
        HashMap<Long, CreationStepDispatchAttempt> perStep = new HashMap<>();
        if (active.size() == 1) {
            for (CreationStep step : creationSteps) {
                dispatchAttempts.findByCreationStepIdAndCreationExecutionAttemptId(step.getId(), active.get(0).getId())
                        .ifPresent(attempt -> perStep.put(step.getId(), attempt));
            }
        }
        CreationRecoveryStateInspector.RecoveryDecision decision = inspector.inspect(creationSteps, active, perStep);
        return Optional.of(new FencedInspection(
                fence, active.size() == 1 ? active.get(0).getId() : null, decision));
    }

    @Transactional
    public boolean apply(FencedInspection inspection) {
        if (inspection == null) {
            return false;
        }
        RecoveryFence fence = inspection.fence();
        CreationRecoveryStateInspector.RecoveryDecision decision = inspection.decision();
        LocalDateTime now = LocalDateTime.now(clock);
        return switch (decision.kind()) {
            case REQUEUE -> creations.requeueRecovered(fence.creationId(), fence.recoveryToken(), now) == 1;
            case REQUEUE_NOT_SENT -> requeueNotSent(inspection, now);
            case FINALIZE_SUCCESS -> finalizeSuccess(inspection, now);
            case FINALIZE_FAILED -> finalizeFailed(inspection, now, false);
            case AMBIGUOUS -> finalizeFailed(inspection, now, true);
            case INCONSISTENT -> quarantine(inspection, now);
        };
    }

    /** Last-resort fenced quarantine for an inspection/data access failure without exposing internals. */
    @Transactional
    public boolean quarantineUnexpected(RecoveryFence fence) {
        if (fence == null) {
            return false;
        }
        List<CreationExecutionAttempt> active = executionAttempts
                .findByCreationIdAndFinishedAtIsNullOrderByIdAsc(fence.creationId());
        List<CreationStep> creationSteps = steps.findByCreationIdOrderByStepIndexAsc(fence.creationId());
        boolean priorSuccess = creationSteps.stream()
                .anyMatch(step -> CreationStepStatus.SUCCEEDED.name().equals(step.getStatus()));
        LocalDateTime now = LocalDateTime.now(clock);
        String status = priorSuccess ? CreationStatus.PARTIAL_SUCCESS.name() : CreationStatus.FAILED.name();
        if (creations.terminalizeRecovered(
                fence.creationId(), fence.recoveryToken(), status,
                CreationRecoveryStateInspector.INCONSISTENT, CreationRecoveryStateInspector.INCONSISTENT, now) != 1) {
            return false;
        }
        return active.size() != 1 || finishAttempt(active.get(0).getId(), CreationRecoveryStateInspector.INCONSISTENT, now);
    }

    private boolean requeueNotSent(FencedInspection inspection, LocalDateTime now) {
        if (inspection.executionAttemptId() == null || inspection.decision().boundary() == null) {
            return quarantine(inspection, now);
        }
        CreationStep step = inspection.decision().boundary();
        RecoveryFence fence = inspection.fence();
        if (dispatchAttempts.markRecoveryRequeuedNotSent(
                step.getId(), inspection.executionAttemptId(), fence.creationId(), fence.recoveryToken(), now) != 1) {
            return false;
        }
        if (steps.resetRecoveredNotSent(step.getId(), fence.creationId(), fence.recoveryToken()) != 1) {
            return false;
        }
        return creations.requeueRecovered(fence.creationId(), fence.recoveryToken(), now) == 1;
    }

    private boolean finalizeSuccess(FencedInspection inspection, LocalDateTime now) {
        if (inspection.executionAttemptId() == null) {
            return quarantine(inspection, now);
        }
        CreationRecoveryStateInspector.RecoveryDecision decision = inspection.decision();
        RecoveryFence fence = inspection.fence();
        if (creations.finalizeRecoveredSuccess(
                fence.creationId(), fence.recoveryToken(), decision.finalModality(), decision.finalAssetId(),
                decision.finalOutputJson(), now) != 1) {
            return false;
        }
        return finishAttempt(inspection.executionAttemptId(), "RECOVERY_FINALIZED_FROM_PERSISTED_RESULT", now);
    }

    private boolean finalizeFailed(FencedInspection inspection, LocalDateTime now, boolean markRunningStepFailed) {
        if (inspection.executionAttemptId() == null) {
            return quarantine(inspection, now);
        }
        CreationRecoveryStateInspector.RecoveryDecision decision = inspection.decision();
        RecoveryFence fence = inspection.fence();
        if (markRunningStepFailed) {
            CreationStep step = decision.boundary();
            if (step == null || steps.failRecoveredRunning(
                    step.getId(), fence.creationId(), fence.recoveryToken(), decision.errorCode(), decision.errorCode(), now) != 1) {
                return false;
            }
            if (dispatchAttempts.finishFailure(
                    step.getId(), inspection.executionAttemptId(), fence.creationId(), fence.recoveryToken(),
                    decision.errorCode(), now) != 1) {
                return false;
            }
        }
        String status = decision.priorSuccess() ? CreationStatus.PARTIAL_SUCCESS.name() : CreationStatus.FAILED.name();
        if (creations.terminalizeRecovered(
                fence.creationId(), fence.recoveryToken(), status, decision.errorCode(), decision.errorCode(), now) != 1) {
            return false;
        }
        return finishAttempt(inspection.executionAttemptId(), decision.errorCode(), now);
    }

    private boolean quarantine(FencedInspection inspection, LocalDateTime now) {
        RecoveryFence fence = inspection.fence();
        CreationRecoveryStateInspector.RecoveryDecision decision = inspection.decision();
        String code = decision.errorCode() == null
                ? CreationRecoveryStateInspector.INCONSISTENT : decision.errorCode();
        String status = decision.priorSuccess() ? CreationStatus.PARTIAL_SUCCESS.name() : CreationStatus.FAILED.name();
        if (creations.terminalizeRecovered(
                fence.creationId(), fence.recoveryToken(), status, code, code, now) != 1) {
            return false;
        }
        return inspection.executionAttemptId() == null || finishAttempt(inspection.executionAttemptId(), code, now);
    }

    private boolean finishAttempt(Long attemptId, String resolutionCode, LocalDateTime now) {
        CreationExecutionAttempt attempt = executionAttempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getFinishedAt() != null) {
            return false;
        }
        attempt.setFinishedAt(now);
        attempt.setResolutionCode(resolutionCode);
        executionAttempts.save(attempt);
        return true;
    }

    public record RecoveryCandidate(Long creationId, String claimToken, LocalDateTime leaseExpiresAt) {
    }

    public record RecoveryFence(Long creationId, String recoveryToken) {
    }

    public record FencedInspection(
            RecoveryFence fence,
            Long executionAttemptId,
            CreationRecoveryStateInspector.RecoveryDecision decision) {
    }
}
