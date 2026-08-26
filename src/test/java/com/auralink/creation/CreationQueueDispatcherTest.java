package com.auralink.creation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.auralink.config.properties.CreationExecutionProperties;

class CreationQueueDispatcherTest {

    @Test
    void refusesToClaimWhileStartupRecoveryGateIsClosed() {
        CreationExecutionTransactionService transactions = mock(CreationExecutionTransactionService.class);
        CreationWorker worker = mock(CreationWorker.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        CreationExecutionProperties properties = new CreationExecutionProperties();
        properties.setEnabled(true);
        CreationRecoveryGate gate = new CreationRecoveryGate();
        CreationQueueDispatcher dispatcher = new CreationQueueDispatcher(
                transactions, worker, properties, gate, executor);

        dispatcher.dispatchOne();

        verifyNoInteractions(transactions, worker, executor);
    }
}
