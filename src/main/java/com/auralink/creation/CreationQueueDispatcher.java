package com.auralink.creation;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.auralink.config.properties.CreationExecutionProperties;

import lombok.RequiredArgsConstructor;

/** Claims at most one oldest queued Creation and never runs provider work itself. */
@Component
@ConditionalOnProperty(prefix = "auralink.creations", name = "enabled", havingValue = "true")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class CreationQueueDispatcher {

    private final CreationExecutionTransactionService transactions;
    private final CreationWorker worker;
    private final CreationExecutionProperties properties;
    private final CreationRecoveryGate recoveryGate;
    @Qualifier("creationWorkerExecutor")
    private final ThreadPoolTaskExecutor executor;
    private final CreationExecutionBoundaryHook boundaryHook;
    private final AtomicBoolean acceptingDispatches = new AtomicBoolean(true);

    /** Compatibility constructor for focused dispatcher tests. */
    public CreationQueueDispatcher(
            CreationExecutionTransactionService transactions,
            CreationWorker worker,
            CreationExecutionProperties properties,
            CreationRecoveryGate recoveryGate,
            ThreadPoolTaskExecutor executor) {
        this(transactions, worker, properties, recoveryGate, executor, NoOpCreationExecutionBoundaryHook.INSTANCE);
    }

    @Scheduled(
            fixedDelayString = "${auralink.creations.dispatch-delay:1000}",
            initialDelayString = "${auralink.creations.dispatch-delay:1000}")
    public void dispatchOne() {
        if (!acceptingDispatches.get() || !properties.isEnabled() || !recoveryGate.isOpen()) {
            return;
        }
        Optional<CreationExecutionTransactionService.ClaimedCreation> claim = transactions.claimOldestQueued();
        if (claim.isEmpty()) {
            return;
        }
        // The claim transaction has returned before a harness may pause here.
        boundaryHook.reached(CreationExecutionBoundary.CLAIM_COMMITTED_BEFORE_SUBMIT);
        if (!acceptingDispatches.get() || !recoveryGate.isOpen()) {
            transactions.returnRejectedSubmissionToQueue(claim.get().id(), claim.get().claimToken());
            return;
        }
        try {
            executor.execute(() -> worker.execute(claim.get()));
        } catch (TaskRejectedException exception) {
            // This can only reopen an untouched pre-dispatch claim. The SQL guard
            // refuses to reset a RUNNING or SEND_STARTED step.
            transactions.returnRejectedSubmissionToQueue(claim.get().id(), claim.get().claimToken());
        }
    }

    void stopDispatching() {
        acceptingDispatches.set(false);
    }
}
