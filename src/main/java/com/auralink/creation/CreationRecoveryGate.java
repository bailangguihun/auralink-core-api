package com.auralink.creation;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * Local lifecycle gate for Creation execution. Database predicates remain the
 * cross-instance correctness boundary; this gate only orders startup/shutdown.
 */
@Component
public class CreationRecoveryGate {

    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private int admittedProviderCalls;

    public boolean isOpen() {
        return open.get() && !shuttingDown.get();
    }

    public void openAfterRecovery() {
        if (!shuttingDown.get()) {
            open.set(true);
        }
    }

    public void close() {
        open.set(false);
    }

    public void beginShutdown() {
        shuttingDown.set(true);
        open.set(false);
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /** Atomically admits a provider boundary before shutdown can close the local gate. */
    public synchronized boolean tryBeginProviderCall() {
        if (!isOpen()) {
            return false;
        }
        admittedProviderCalls++;
        return true;
    }

    public synchronized void finishProviderCall() {
        if (admittedProviderCalls > 0) {
            admittedProviderCalls--;
        }
    }
}
