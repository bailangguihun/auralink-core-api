package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import com.auralink.config.properties.CreationExecutionProperties;

class CreationRecoveryCoordinatorTest {

    @Test
    void startupRecoveryDrainsBeforeOpeningTheGate() {
        CreationRecoveryTransactionService transactions = mock(CreationRecoveryTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(transactions.candidates()).thenReturn(List.of());
        CreationRecoveryGate gate = new CreationRecoveryGate();
        CreationRecoveryCoordinator coordinator = new CreationRecoveryCoordinator(
                transactions, gate, properties(), scheduler);

        coordinator.onApplicationReady();

        assertThat(gate.isOpen()).isTrue();
        assertThat(coordinator.isStartupInitialized()).isTrue();
        verify(transactions).candidates();
    }

    @Test
    void databaseWideStartupFailureLeavesTheGateClosedForLaterRetry() {
        CreationRecoveryTransactionService transactions = mock(CreationRecoveryTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doThrow(new IllegalStateException("not-safe-to-log")).when(transactions).candidates();
        CreationRecoveryGate gate = new CreationRecoveryGate();
        CreationRecoveryCoordinator coordinator = new CreationRecoveryCoordinator(
                transactions, gate, properties(), scheduler);

        coordinator.onApplicationReady();

        assertThat(gate.isOpen()).isFalse();
        assertThat(coordinator.isStartupInitialized()).isTrue();
    }

    @Test
    void nonEmptyFinalPermittedStartupBatchLeavesTheGateClosed() {
        CreationRecoveryTransactionService transactions = mock(CreationRecoveryTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        CreationRecoveryTransactionService.RecoveryCandidate candidate = candidate(7L);
        when(transactions.candidates()).thenReturn(List.of(candidate));
        when(transactions.fence(candidate)).thenReturn(Optional.empty());
        CreationRecoveryGate gate = new CreationRecoveryGate();
        CreationRecoveryCoordinator coordinator = new CreationRecoveryCoordinator(
                transactions, gate, properties(1), scheduler);

        coordinator.onApplicationReady();

        assertThat(gate.isOpen()).isFalse();
        verify(transactions, org.mockito.Mockito.times(1)).candidates();
    }

    @Test
    void recoveredFirstBatchFollowedByEmptySecondBatchOpensTheProductionGate() {
        CreationRecoveryTransactionService transactions = mock(CreationRecoveryTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        CreationRecoveryTransactionService.RecoveryCandidate candidate = candidate(7L);
        CreationRecoveryTransactionService.RecoveryFence fence =
                new CreationRecoveryTransactionService.RecoveryFence(7L, "recovery-fence");
        CreationRecoveryTransactionService.FencedInspection inspection =
                new CreationRecoveryTransactionService.FencedInspection(fence, null, null);
        when(transactions.candidates()).thenReturn(List.of(candidate), List.of());
        when(transactions.fence(candidate)).thenReturn(Optional.of(fence));
        when(transactions.inspect(fence)).thenReturn(Optional.of(inspection));
        when(transactions.apply(inspection)).thenReturn(true);
        CreationRecoveryGate gate = new CreationRecoveryGate();
        CreationRecoveryCoordinator coordinator = new CreationRecoveryCoordinator(
                transactions, gate, properties(2), scheduler);

        coordinator.onApplicationReady();

        assertThat(gate.isOpen()).isTrue();
        verify(transactions, org.mockito.Mockito.times(2)).candidates();
        verify(transactions).apply(inspection);
    }

    @Test
    void twoNonEmptyPermittedStartupBatchesRemainFailClosed() {
        CreationRecoveryTransactionService transactions = mock(CreationRecoveryTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        CreationRecoveryTransactionService.RecoveryCandidate first = candidate(7L);
        CreationRecoveryTransactionService.RecoveryCandidate second = candidate(8L);
        when(transactions.candidates()).thenReturn(List.of(first), List.of(second));
        when(transactions.fence(first)).thenReturn(Optional.empty());
        when(transactions.fence(second)).thenReturn(Optional.empty());
        CreationRecoveryGate gate = new CreationRecoveryGate();
        CreationRecoveryCoordinator coordinator = new CreationRecoveryCoordinator(
                transactions, gate, properties(2), scheduler);

        coordinator.onApplicationReady();

        assertThat(gate.isOpen()).isFalse();
        verify(transactions, org.mockito.Mockito.times(2)).candidates();
    }

    @Test
    void scheduledRecoverySweepsDoNotOverlapInOneProcess() throws Exception {
        CreationRecoveryTransactionService transactions = mock(CreationRecoveryTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(transactions.candidates())
                .thenReturn(List.of())
                .thenAnswer(ignored -> {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test recovery release timed out");
                    }
                    return List.of();
                });
        CreationRecoveryGate gate = new CreationRecoveryGate();
        gate.openAfterRecovery();
        CreationRecoveryCoordinator coordinator = new CreationRecoveryCoordinator(
                transactions, gate, properties(), scheduler);
        coordinator.onApplicationReady();
        verify(transactions).candidates();
        clearInvocations(transactions);

        Thread first = new Thread(coordinator::scheduledRecovery, "round9cb2-test-recovery-one");
        Thread second = new Thread(coordinator::scheduledRecovery, "round9cb2-test-recovery-two");
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        second.start();
        second.join(5_000);
        assertThat(first.isAlive()).isTrue();
        assertThat(second.isAlive()).isFalse();
        release.countDown();
        first.join(5_000);

        verify(transactions, org.mockito.Mockito.times(1)).candidates();
        assertThat(first.isAlive()).isFalse();
    }

    private static CreationExecutionProperties properties() {
        return properties(20);
    }

    private static CreationExecutionProperties properties(int startupMaxBatches) {
        CreationExecutionProperties properties = new CreationExecutionProperties();
        properties.setRecoveryInterval(Duration.ofMinutes(1));
        properties.setStartupMaxBatches(startupMaxBatches);
        return properties;
    }

    private static CreationRecoveryTransactionService.RecoveryCandidate candidate(long id) {
        return new CreationRecoveryTransactionService.RecoveryCandidate(
                id, "claim-" + id, LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
