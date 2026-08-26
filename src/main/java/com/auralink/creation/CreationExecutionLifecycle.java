package com.auralink.creation;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** Explicit shutdown ordering for the bounded Creation runtime. */
@Component
@ConditionalOnProperty(prefix = "auralink.creations", name = "enabled", havingValue = "true")
public class CreationExecutionLifecycle implements SmartLifecycle {

    private final CreationRecoveryGate gate;
    private final CreationQueueDispatcher dispatcher;
    private final CreationRecoveryCoordinator recovery;
    private final ThreadPoolTaskExecutor workers;
    private final ThreadPoolTaskScheduler heartbeatScheduler;
    private final ThreadPoolTaskScheduler recoveryScheduler;
    private final CreationExecutionBoundaryHook boundaryHook;
    private volatile boolean running;

    /** Compatibility constructor for lifecycle ordering tests. */
    public CreationExecutionLifecycle(
            CreationRecoveryGate gate,
            CreationQueueDispatcher dispatcher,
            CreationRecoveryCoordinator recovery,
            ThreadPoolTaskExecutor workers,
            ThreadPoolTaskScheduler heartbeatScheduler,
            ThreadPoolTaskScheduler recoveryScheduler) {
        this(gate, dispatcher, recovery, workers, heartbeatScheduler, recoveryScheduler,
                NoOpCreationExecutionBoundaryHook.INSTANCE);
    }

    @Autowired
    public CreationExecutionLifecycle(
            CreationRecoveryGate gate,
            CreationQueueDispatcher dispatcher,
            CreationRecoveryCoordinator recovery,
            @Qualifier("creationWorkerExecutor") ThreadPoolTaskExecutor workers,
            @Qualifier("creationHeartbeatScheduler") ThreadPoolTaskScheduler heartbeatScheduler,
            @Qualifier("creationRecoveryScheduler") ThreadPoolTaskScheduler recoveryScheduler,
            CreationExecutionBoundaryHook boundaryHook) {
        this.gate = gate;
        this.dispatcher = dispatcher;
        this.recovery = recovery;
        this.workers = workers;
        this.heartbeatScheduler = heartbeatScheduler;
        this.recoveryScheduler = recoveryScheduler;
        this.boundaryHook = boundaryHook;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        // Order is intentional: no claim/recovery can start before active workers are awaited.
        gate.beginShutdown();
        boundaryHook.reached(CreationExecutionBoundary.GRACEFUL_SHUTDOWN_DURING_AWAIT);
        dispatcher.stopDispatching();
        recovery.stopRecovery();
        workers.shutdown();
        // The worker shutdown above waits for bounded active work while lease heartbeats continue.
        heartbeatScheduler.shutdown();
        recoveryScheduler.shutdown();
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
