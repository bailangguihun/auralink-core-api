package com.auralink.config.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;

/** Bounded, non-secret controls for the persisted Creation execution foundation. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "auralink.creations")
public class CreationExecutionProperties {

    private boolean enabled = false;

    /** ROUND 9B.2 deliberately has one serial persisted Creation worker. */
    @Min(1)
    @Max(1)
    private int workerCount = 1;

    @Min(1)
    @Max(1_000)
    private int queueCapacity = 16;

    /** Bounded handoff between the dispatcher and the sole worker thread. */
    @Min(1)
    @Max(1_000)
    private int executorQueueCapacity = 1;

    @NotNull
    private Duration leaseDuration = Duration.ofMinutes(15);

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(30);

    @NotNull
    private Duration recoveryGrace = Duration.ofSeconds(90);

    @NotNull
    private Duration recoveryInterval = Duration.ofSeconds(60);

    @Min(1)
    @Max(100)
    private int recoveryBatchSize = 50;

    @Min(1)
    @Max(100)
    private int startupMaxBatches = 20;

    @NotNull
    private Duration recoveryFenceLease = Duration.ofMinutes(5);

    @NotNull
    private Duration dispatchDelay = Duration.ofSeconds(1);

    @NotNull
    private Duration shutdownAwait = Duration.ofSeconds(30);

    @Min(1)
    @Max(100)
    private int defaultPageSize = 20;

    @Min(1)
    @Max(100)
    private int maxPageSize = 100;

    @AssertTrue(message = "Creation heartbeat interval must be positive and shorter than the lease duration")
    public boolean isHeartbeatTimingSafe() {
        return positive(leaseDuration)
                && positive(heartbeatInterval)
                && heartbeatInterval.compareTo(leaseDuration) < 0;
    }

    @AssertTrue(message = "Creation recovery durations must be positive and the fence lease must be at least five minutes")
    public boolean isRecoveryTimingSafe() {
        return positive(recoveryGrace)
                && positive(recoveryInterval)
                && recoveryFenceLease != null
                && recoveryFenceLease.compareTo(Duration.ofMinutes(5)) >= 0;
    }

    @AssertTrue(message = "Creation shutdown wait must be positive and bounded")
    public boolean isShutdownAwaitSafe() {
        return shutdownAwait != null
                && !shutdownAwait.isNegative()
                && !shutdownAwait.isZero()
                && shutdownAwait.compareTo(Duration.ofMinutes(5)) <= 0;
    }

    private boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
