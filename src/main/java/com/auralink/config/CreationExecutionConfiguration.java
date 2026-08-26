package com.auralink.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.auralink.config.properties.CreationExecutionProperties;

/** Opt-in, single-threaded executor for persisted Creation work only. */
@Configuration
@ConditionalOnProperty(prefix = "auralink.creations", name = "enabled", havingValue = "true")
public class CreationExecutionConfiguration {

    @Bean(name = "creationWorkerExecutor")
    public ThreadPoolTaskExecutor creationWorkerExecutor(CreationExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.getExecutorQueueCapacity());
        executor.setThreadNamePrefix("auralink-creation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(toBoundedSeconds(properties.getShutdownAwait()));
        executor.setAcceptTasksAfterContextClose(false);
        executor.setPhase(Integer.MAX_VALUE - 100);
        return executor;
    }

    /** Dedicated single-thread timer; it never shares provider worker capacity. */
    @Bean(name = "creationHeartbeatScheduler")
    public ThreadPoolTaskScheduler creationHeartbeatScheduler(CreationExecutionProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("auralink-creation-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(toBoundedSeconds(properties.getShutdownAwait()));
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setPhase(Integer.MAX_VALUE - 100);
        return scheduler;
    }

    /** Recovery is isolated from dispatcher and heartbeat timers. */
    @Bean(name = "creationRecoveryScheduler")
    public ThreadPoolTaskScheduler creationRecoveryScheduler(CreationExecutionProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("auralink-creation-recovery-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(toBoundedSeconds(properties.getShutdownAwait()));
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setPhase(Integer.MAX_VALUE - 100);
        return scheduler;
    }

    private int toBoundedSeconds(java.time.Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Creation shutdown await duration must be positive");
        }
        long seconds = Math.max(1L, duration.toSeconds());
        return (int) Math.min(seconds, 300L);
    }
}
