package com.auralink.ops.round9cc;

/** Safe signal used when a private harness barrier is not released in time. */
public final class Round9CcFailpointTimeoutException extends IllegalStateException {

    public Round9CcFailpointTimeoutException() {
        super("ROUND 9C-C failpoint timed out");
    }
}
