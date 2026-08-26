package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import com.auralink.config.properties.CreationExecutionProperties;

class CreationLeaseHeartbeatServiceTest {

    @Test
    void refreshesOnlyTheMatchingClaimUsingTheInjectedUtcClock() {
        CreationLeaseHeartbeatTransactionService transactions = mock(CreationLeaseHeartbeatTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        when(transactions.refresh(eq(7L), eq("claim"), any(), any())).thenReturn(1);
        CreationExecutionProperties properties = new CreationExecutionProperties();
        properties.setLeaseDuration(Duration.ofMinutes(15));
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
        CreationLeaseHeartbeatService service = new CreationLeaseHeartbeatService(
                transactions, properties, clock, scheduler);

        CreationLeaseHeartbeatService.LeaseHeartbeatHandle handle = service.start(7L, "claim");
        service.heartbeat(handle);
        handle.close();

        ArgumentCaptor<LocalDateTime> lease = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactions).refresh(eq(7L), eq("claim"), lease.capture(), now.capture());
        assertThat(now.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 23, 0, 0));
        assertThat(lease.getValue()).isEqualTo(LocalDateTime.of(2026, 8, 23, 0, 15));
        verify(future).cancel(false);
    }

    @Test
    void zeroRowHeartbeatFencesTheOldWorkerWithoutTreatingATransientExceptionAsLoss() {
        CreationLeaseHeartbeatTransactionService transactions = mock(CreationLeaseHeartbeatTransactionService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> scheduledHeartbeat = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).scheduleAtFixedRate(scheduledHeartbeat.capture(), any(Duration.class));
        when(transactions.refresh(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("jdbc secret"))
                .thenReturn(0);
        CreationLeaseHeartbeatService service = new CreationLeaseHeartbeatService(
                transactions, new CreationExecutionProperties(), Clock.systemUTC(), scheduler);
        CreationLeaseHeartbeatService.LeaseHeartbeatHandle handle = service.start(7L, "claim");

        scheduledHeartbeat.getValue().run();
        assertThat(handle.ownershipLost()).isFalse();
        scheduledHeartbeat.getValue().run();

        assertThat(handle.ownershipLost()).isTrue();
        verify(future).cancel(false);
    }
}
