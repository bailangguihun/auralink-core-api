package com.auralink.creation;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.auralink.config.properties.CreationExecutionProperties;

class CreationExecutionBoundaryLifecycleTest {

    @Test
    void dispatcherBoundaryOccursOnlyAfterTheClaimTransactionReturns() {
        CreationExecutionTransactionService transactions = mock(CreationExecutionTransactionService.class);
        CreationWorker worker = mock(CreationWorker.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        CreationExecutionBoundaryHook hook = mock(CreationExecutionBoundaryHook.class);
        CreationExecutionProperties properties = new CreationExecutionProperties();
        properties.setEnabled(true);
        CreationRecoveryGate gate = new CreationRecoveryGate();
        gate.openAfterRecovery();
        var claim = new CreationExecutionTransactionService.ClaimedCreation(7L, "test-claim");
        when(transactions.claimOldestQueued()).thenReturn(Optional.of(claim));
        CreationQueueDispatcher dispatcher = new CreationQueueDispatcher(
                transactions, worker, properties, gate, executor, hook);

        dispatcher.dispatchOne();

        InOrder order = inOrder(transactions, hook, executor);
        order.verify(transactions).claimOldestQueued();
        order.verify(hook).reached(CreationExecutionBoundary.CLAIM_COMMITTED_BEFORE_SUBMIT);
        order.verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void startupAndScheduledRecoveryBoundariesRemainProviderFreeCoordinatorBoundaries() {
        CreationRecoveryTransactionService transactions = mock(CreationRecoveryTransactionService.class);
        when(transactions.candidates()).thenReturn(List.of());
        CreationExecutionBoundaryHook hook = mock(CreationExecutionBoundaryHook.class);
        CreationRecoveryGate gate = new CreationRecoveryGate();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        CreationExecutionProperties properties = new CreationExecutionProperties();
        properties.setRecoveryInterval(Duration.ofMinutes(1));
        CreationRecoveryCoordinator coordinator = new CreationRecoveryCoordinator(
                transactions, gate, properties, scheduler, hook);

        coordinator.onApplicationReady();
        coordinator.scheduledRecovery();

        verify(hook).reached(CreationExecutionBoundary.STARTUP_RECOVERY_GATE_CLOSED);
        verify(hook).reached(CreationExecutionBoundary.SCHEDULED_RECOVERY_SWEEP);
    }

    @Test
    void shutdownBoundaryRunsAfterGateClosureAndBeforeWorkerAwait() {
        CreationRecoveryGate gate = mock(CreationRecoveryGate.class);
        CreationQueueDispatcher dispatcher = mock(CreationQueueDispatcher.class);
        CreationRecoveryCoordinator recovery = mock(CreationRecoveryCoordinator.class);
        ThreadPoolTaskExecutor workers = mock(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskScheduler heartbeats = mock(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler recoveryScheduler = mock(ThreadPoolTaskScheduler.class);
        CreationExecutionBoundaryHook hook = mock(CreationExecutionBoundaryHook.class);
        CreationExecutionLifecycle lifecycle = new CreationExecutionLifecycle(
                gate, dispatcher, recovery, workers, heartbeats, recoveryScheduler, hook);

        lifecycle.start();
        lifecycle.stop();

        InOrder order = inOrder(gate, hook, dispatcher, recovery, workers, heartbeats, recoveryScheduler);
        order.verify(gate).beginShutdown();
        order.verify(hook).reached(CreationExecutionBoundary.GRACEFUL_SHUTDOWN_DURING_AWAIT);
        order.verify(dispatcher).stopDispatching();
        order.verify(recovery).stopRecovery();
        order.verify(workers).shutdown();
    }
}
