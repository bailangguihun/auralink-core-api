package com.auralink.creation;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class CreationExecutionLifecycleTest {

    @Test
    void shutdownClosesTheGateBeforeStoppingClaimsAndKeepsHeartbeatsUntilWorkersAreAwaited() {
        CreationRecoveryGate gate = mock(CreationRecoveryGate.class);
        CreationQueueDispatcher dispatcher = mock(CreationQueueDispatcher.class);
        CreationRecoveryCoordinator recovery = mock(CreationRecoveryCoordinator.class);
        ThreadPoolTaskExecutor workers = mock(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskScheduler heartbeats = mock(ThreadPoolTaskScheduler.class);
        ThreadPoolTaskScheduler recoveryScheduler = mock(ThreadPoolTaskScheduler.class);
        CreationExecutionLifecycle lifecycle = new CreationExecutionLifecycle(
                gate, dispatcher, recovery, workers, heartbeats, recoveryScheduler);

        lifecycle.start();
        lifecycle.stop();

        InOrder order = inOrder(gate, dispatcher, recovery, workers, heartbeats, recoveryScheduler);
        order.verify(gate).beginShutdown();
        order.verify(dispatcher).stopDispatching();
        order.verify(recovery).stopRecovery();
        order.verify(workers).shutdown();
        order.verify(heartbeats).shutdown();
        order.verify(recoveryScheduler).shutdown();
    }
}
