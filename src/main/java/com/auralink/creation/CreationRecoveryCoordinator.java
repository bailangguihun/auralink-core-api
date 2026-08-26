package com.auralink.creation;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.auralink.config.properties.CreationExecutionProperties;

import lombok.extern.slf4j.Slf4j;

/** Lifecycle-owned, provider-free stale-work recovery coordinator. */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "auralink.creations", name = "enabled", havingValue = "true")
public class CreationRecoveryCoordinator {

    private final CreationRecoveryTransactionService transactions;
    private final CreationRecoveryGate gate;
    private final CreationExecutionProperties properties;
    private final TaskScheduler scheduler;
    private final CreationExecutionBoundaryHook boundaryHook;
    private final AtomicBoolean sweeping = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean startupInitialized = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> periodicTask;

    /** Compatibility constructor for existing coordinator tests. */
    public CreationRecoveryCoordinator(
            CreationRecoveryTransactionService transactions,
            CreationRecoveryGate gate,
            CreationExecutionProperties properties,
            TaskScheduler scheduler) {
        this(transactions, gate, properties, scheduler, NoOpCreationExecutionBoundaryHook.INSTANCE);
    }

    @Autowired
    public CreationRecoveryCoordinator(
            CreationRecoveryTransactionService transactions,
            CreationRecoveryGate gate,
            CreationExecutionProperties properties,
            @Qualifier("creationRecoveryScheduler") TaskScheduler scheduler,
            CreationExecutionBoundaryHook boundaryHook) {
        this.transactions = transactions;
        this.gate = gate;
        this.properties = properties;
        this.scheduler = scheduler;
        this.boundaryHook = boundaryHook;
    }

    /** Runs synchronously after Flyway/JPA initialization and before the gate may open. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runStartupRecovery();
        startupInitialized.set(true);
        if (!stopped.get()) {
            periodicTask = scheduler.scheduleWithFixedDelay(this::scheduledRecovery, properties.getRecoveryInterval());
        }
    }

    public void scheduledRecovery() {
        if (stopped.get() || gate.isShuttingDown() || !startupInitialized.get()) {
            return;
        }
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            boundaryHook.reached(CreationExecutionBoundary.SCHEDULED_RECOVERY_SWEEP);
            if (!gate.isOpen()) {
                runStartupRecovery();
            } else {
                recoverOneBatch();
            }
        } finally {
            sweeping.set(false);
        }
    }

    public void runStartupRecovery() {
        if (stopped.get() || gate.isShuttingDown()) {
            return;
        }
        gate.close();
        boundaryHook.reached(CreationExecutionBoundary.STARTUP_RECOVERY_GATE_CLOSED);
        try {
            for (int batch = 0; batch < properties.getStartupMaxBatches(); batch++) {
                List<CreationRecoveryTransactionService.RecoveryCandidate> candidates = transactions.candidates();
                if (candidates.isEmpty()) {
                    gate.openAfterRecovery();
                    return;
                }
                recover(candidates);
            }
            // The bounded cycle has intentionally not proven that the backlog is drained.
            gate.close();
        } catch (RuntimeException exception) {
            gate.close();
            // No exception value: SQL/JDBC detail is not safe operational output.
            log.warn("Creation startup recovery did not complete; dispatcher remains gated");
        }
    }

    public void recoverOneBatch() {
        if (stopped.get() || gate.isShuttingDown()) {
            return;
        }
        try {
            recover(transactions.candidates());
        } catch (RuntimeException exception) {
            log.warn("Creation periodic recovery sweep did not complete");
        }
    }

    private void recover(List<CreationRecoveryTransactionService.RecoveryCandidate> candidates) {
        for (CreationRecoveryTransactionService.RecoveryCandidate candidate : candidates) {
            java.util.Optional<CreationRecoveryTransactionService.RecoveryFence> fence = java.util.Optional.empty();
            try {
                fence = transactions.fence(candidate);
                fence.flatMap(transactions::inspect).ifPresent(transactions::apply);
            } catch (RuntimeException exception) {
                // One corrupt item must not prevent other independently fenced items.
                fence.ifPresent(transactions::quarantineUnexpected);
                log.warn("Creation recovery item did not complete and requires later operator review");
            }
        }
    }

    public void stopRecovery() {
        stopped.set(true);
        ScheduledFuture<?> scheduled = periodicTask;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
    }

    boolean isStartupInitialized() {
        return startupInitialized.get();
    }
}
