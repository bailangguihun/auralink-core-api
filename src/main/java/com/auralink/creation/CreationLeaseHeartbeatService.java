package com.auralink.creation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.auralink.config.properties.CreationExecutionProperties;
import lombok.extern.slf4j.Slf4j;

/** Bounded per-claim lease renewal, intentionally separate from provider workers. */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "auralink.creations", name = "enabled", havingValue = "true")
public class CreationLeaseHeartbeatService {

    private final CreationLeaseHeartbeatTransactionService transactions;
    private final CreationExecutionProperties properties;
    private final Clock clock;
    private final TaskScheduler scheduler;

    public CreationLeaseHeartbeatService(
            CreationLeaseHeartbeatTransactionService transactions,
            CreationExecutionProperties properties,
            Clock clock,
            @Qualifier("creationHeartbeatScheduler") TaskScheduler scheduler) {
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public LeaseHeartbeatHandle start(Long creationId, String claimToken) {
        LeaseHeartbeatHandle handle = new LeaseHeartbeatHandle(creationId, claimToken);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> heartbeat(handle), properties.getHeartbeatInterval());
        handle.setFuture(future);
        return handle;
    }

    void heartbeat(LeaseHeartbeatHandle handle) {
        if (handle.closed.get() || handle.ownershipLost.get()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            int updated = transactions.refresh(
                    handle.creationId, handle.claimToken, now.plus(properties.getLeaseDuration()), now);
            if (updated == 0) {
                handle.ownershipLost.set(true);
                handle.close();
            }
        } catch (RuntimeException exception) {
            // Deliberately avoid exception text: JDBC details and bound values are unsafe here.
            log.warn("Creation lease heartbeat did not complete; a later bounded heartbeat will retry");
        }
    }

    public static final class LeaseHeartbeatHandle implements AutoCloseable {
        private final Long creationId;
        private final String claimToken;
        private final AtomicBoolean ownershipLost = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        private LeaseHeartbeatHandle(Long creationId, String claimToken) {
            this.creationId = creationId;
            this.claimToken = claimToken;
        }

        private void setFuture(ScheduledFuture<?> future) {
            this.future = future;
            if (closed.get() && future != null) {
                future.cancel(false);
            }
        }

        public boolean ownershipLost() {
            return ownershipLost.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduled = future;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
            }
        }
    }
}
