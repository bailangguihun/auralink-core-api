package com.auralink.creation;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.auralink.entity.CreationExecutionAttempt;
import com.auralink.entity.CreationStep;
import com.auralink.entity.CreationStepDispatchAttempt;
import com.auralink.provider.qwen.PaintingPoemResultValidator;
import com.auralink.service.media.MediaAssetStorageService;
import com.auralink.workflow.WorkflowModality;

/** Pure, provider-free recovery classifier for one fenced Creation snapshot. */
@Component
public class CreationRecoveryStateInspector {

    static final String AMBIGUOUS = "PROVIDER_DISPATCH_AMBIGUOUS";
    static final String INCONSISTENT = "CREATION_STATE_INCONSISTENT";
    static final String RESULT_INCONSISTENT = "CREATION_RESULT_PERSISTENCE_INCONSISTENT";

    private final PaintingPoemResultValidator poemValidator;
    private final MediaAssetStorageService mediaStorage;

    public CreationRecoveryStateInspector(
            PaintingPoemResultValidator poemValidator,
            MediaAssetStorageService mediaStorage) {
        this.poemValidator = poemValidator;
        this.mediaStorage = mediaStorage;
    }

    public RecoveryDecision inspect(
            List<CreationStep> steps,
            List<CreationExecutionAttempt> unfinishedAttempts,
            Map<Long, CreationStepDispatchAttempt> activeDispatchAttempts) {
        if (steps == null || steps.isEmpty()) {
            return RecoveryDecision.inconsistent(INCONSISTENT);
        }
        boolean invalidActiveAttempt = unfinishedAttempts == null || unfinishedAttempts.size() != 1;
        int running = 0;
        int failed = 0;
        int firstNonSucceeded = -1;
        boolean priorSuccess = false;
        for (int index = 0; index < steps.size(); index++) {
            CreationStep step = steps.get(index);
            if (step.getStepIndex() != index || !knownStatus(step.getStatus())) {
                return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
            }
            if (CreationStepStatus.SUCCEEDED.name().equals(step.getStatus())) {
                if (firstNonSucceeded >= 0 || !validSucceededOutput(step)) {
                    return RecoveryDecision.inconsistent(
                            validSucceededOutput(step) ? INCONSISTENT : RESULT_INCONSISTENT, priorSuccess);
                }
                priorSuccess = true;
                continue;
            }
            if (firstNonSucceeded < 0) {
                firstNonSucceeded = index;
            }
            if (CreationStepStatus.RUNNING.name().equals(step.getStatus())) {
                running++;
            } else if (CreationStepStatus.FAILED.name().equals(step.getStatus())) {
                failed++;
            }
        }
        if (running > 1 || failed > 1) {
            return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
        }
        if (invalidActiveAttempt) {
            return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
        }
        if (firstNonSucceeded < 0) {
            CreationStep terminal = steps.get(steps.size() - 1);
            return validTerminal(terminal)
                    ? RecoveryDecision.finalizeSuccess(terminal)
                    : RecoveryDecision.inconsistent(RESULT_INCONSISTENT, priorSuccess);
        }

        CreationStep boundary = steps.get(firstNonSucceeded);
        for (int index = firstNonSucceeded + 1; index < steps.size(); index++) {
            if (!CreationStepStatus.PENDING.name().equals(steps.get(index).getStatus())) {
                return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
            }
            if (!ProviderDispatchState.NOT_SENT.name().equals(steps.get(index).getProviderDispatchState())
                    || steps.get(index).getProviderRequestKey() != null) {
                return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
            }
        }
        if (CreationStepStatus.PENDING.name().equals(boundary.getStatus())) {
            if (!ProviderDispatchState.NOT_SENT.name().equals(boundary.getProviderDispatchState())
                    || boundary.getProviderRequestKey() != null) {
                return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
            }
            return RecoveryDecision.requeue();
        }
        if (CreationStepStatus.FAILED.name().equals(boundary.getStatus())) {
            if (ProviderDispatchState.RESULT_PERSISTED.name().equals(boundary.getProviderDispatchState())) {
                return RecoveryDecision.inconsistent(RESULT_INCONSISTENT, priorSuccess);
            }
            String code = ProviderDispatchState.SEND_STARTED.name().equals(boundary.getProviderDispatchState())
                    ? AMBIGUOUS : "CREATION_RECOVERY_FINALIZED_FAILED";
            return RecoveryDecision.finalizeFailed(boundary, priorSuccess, code);
        }
        if (!CreationStepStatus.RUNNING.name().equals(boundary.getStatus()) || running != 1) {
            return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
        }
        if (ProviderDispatchState.RESULT_PERSISTED.name().equals(boundary.getProviderDispatchState())) {
            return RecoveryDecision.inconsistent(RESULT_INCONSISTENT, priorSuccess);
        }
        CreationStepDispatchAttempt dispatch = activeDispatchAttempts.get(boundary.getId());
        if (dispatch == null || !same(boundary.getProviderDispatchState(), dispatch.getDispatchState())) {
            return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
        }
        if (ProviderDispatchState.NOT_SENT.name().equals(boundary.getProviderDispatchState())) {
            if (boundary.getProviderRequestKey() != null || dispatch.getProviderRequestKey() != null) {
                return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
            }
            return RecoveryDecision.requeueNotSent(boundary);
        }
        if (ProviderDispatchState.SEND_STARTED.name().equals(boundary.getProviderDispatchState())) {
            if (boundary.getProviderRequestKey() == null || dispatch.getProviderRequestKey() == null
                    || !boundary.getProviderRequestKey().equals(dispatch.getProviderRequestKey())) {
                return RecoveryDecision.inconsistent(INCONSISTENT, priorSuccess);
            }
            return RecoveryDecision.ambiguous(boundary, priorSuccess);
        }
        return RecoveryDecision.inconsistent(RESULT_INCONSISTENT, priorSuccess);
    }

    private boolean knownStatus(String value) {
        return CreationStepStatus.PENDING.name().equals(value)
                || CreationStepStatus.RUNNING.name().equals(value)
                || CreationStepStatus.SUCCEEDED.name().equals(value)
                || CreationStepStatus.FAILED.name().equals(value);
    }

    private boolean validSucceededOutput(CreationStep step) {
        if (!ProviderDispatchState.RESULT_PERSISTED.name().equals(step.getProviderDispatchState())) {
            return false;
        }
        if (WorkflowModality.PAINTING.name().equals(step.getOutputModality())) {
            if (step.getOutputAsset() == null
                    || step.getOutputAsset().getSha256() == null
                    || step.getOutputAsset().getFileSize() == null
                    || step.getOutputAsset().getFileSize() <= 0
                    || !("image/png".equals(step.getOutputAsset().getMimeType())
                            || "image/jpeg".equals(step.getOutputAsset().getMimeType()))) {
                return false;
            }
            try {
                MediaAssetStorageService.MediaAssetStoredResource stored = mediaStorage.resolve(step.getOutputAsset());
                return stored != null
                        && stored.resource() != null
                        && stored.resource().exists()
                        && stored.resource().isReadable()
                        && stored.contentLength() == step.getOutputAsset().getFileSize();
            } catch (RuntimeException exception) {
                // A recovery classifier must fail closed when the one referenced
                // managed result cannot be resolved. It never scans storage.
                return false;
            }
        }
        if (WorkflowModality.POEM.name().equals(step.getOutputModality())) {
            if (step.getOutputJson() == null || step.getOutputAsset() != null) {
                return false;
            }
            try {
                poemValidator.validate(step.getOutputJson());
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return false;
    }

    private boolean validTerminal(CreationStep step) {
        return validSucceededOutput(step)
                && (WorkflowModality.PAINTING.name().equals(step.getOutputModality())
                        || WorkflowModality.POEM.name().equals(step.getOutputModality()));
    }

    private boolean same(String left, String right) {
        return left != null && left.equals(right);
    }

    public enum Kind {
        REQUEUE,
        REQUEUE_NOT_SENT,
        FINALIZE_SUCCESS,
        FINALIZE_FAILED,
        AMBIGUOUS,
        INCONSISTENT
    }

    public record RecoveryDecision(
            Kind kind,
            CreationStep boundary,
            boolean priorSuccess,
            String errorCode,
            String finalModality,
            Long finalAssetId,
            String finalOutputJson) {
        static RecoveryDecision requeue() {
            return new RecoveryDecision(Kind.REQUEUE, null, false, null, null, null, null);
        }

        static RecoveryDecision requeueNotSent(CreationStep boundary) {
            return new RecoveryDecision(Kind.REQUEUE_NOT_SENT, boundary, false, null, null, null, null);
        }

        static RecoveryDecision finalizeSuccess(CreationStep terminal) {
            return new RecoveryDecision(
                    Kind.FINALIZE_SUCCESS, terminal, true, null, terminal.getOutputModality(),
                    terminal.getOutputAsset() == null ? null : terminal.getOutputAsset().getId(), terminal.getOutputJson());
        }

        static RecoveryDecision finalizeFailed(CreationStep boundary, boolean priorSuccess, String code) {
            return new RecoveryDecision(Kind.FINALIZE_FAILED, boundary, priorSuccess, code, null, null, null);
        }

        static RecoveryDecision ambiguous(CreationStep boundary, boolean priorSuccess) {
            return new RecoveryDecision(Kind.AMBIGUOUS, boundary, priorSuccess, AMBIGUOUS, null, null, null);
        }

        static RecoveryDecision inconsistent(String code) {
            return inconsistent(code, false);
        }

        static RecoveryDecision inconsistent(String code, boolean priorSuccess) {
            return new RecoveryDecision(Kind.INCONSISTENT, null, priorSuccess, code, null, null, null);
        }
    }
}
