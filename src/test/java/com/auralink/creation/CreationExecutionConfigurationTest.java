package com.auralink.creation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;


import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.auralink.config.CreationExecutionConfiguration;
import com.auralink.config.properties.CreationExecutionProperties;

class CreationExecutionConfigurationTest {

    @Test
    void createsExactlyOneBoundedWorkerWithoutCallerRunsFallback() {
        CreationExecutionProperties properties = new CreationExecutionProperties();
        properties.setExecutorQueueCapacity(3);
        properties.setShutdownAwait(Duration.ofSeconds(7));

        ThreadPoolTaskExecutor executor = new CreationExecutionConfiguration().creationWorkerExecutor(properties);
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(3);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("auralink-creation-");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectsUnsafeHeartbeatAndRecoveryTimingCombinations() {
        CreationExecutionProperties properties = new CreationExecutionProperties();
        properties.setHeartbeatInterval(Duration.ofMinutes(15));
        assertThat(properties.isHeartbeatTimingSafe()).isFalse();
        properties.setHeartbeatInterval(Duration.ofSeconds(30));
        properties.setRecoveryGrace(Duration.ZERO);
        assertThat(properties.isRecoveryTimingSafe()).isFalse();
        properties.setShutdownAwait(Duration.ofMinutes(6));
        assertThat(properties.isShutdownAwaitSafe()).isFalse();
    }
}
