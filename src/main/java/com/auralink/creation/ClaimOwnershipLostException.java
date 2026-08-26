package com.auralink.creation;

/** Internal control signal: a stale worker must not overwrite newer ownership. */
final class ClaimOwnershipLostException extends RuntimeException {

    ClaimOwnershipLostException() {
        super("Creation claim ownership is no longer current");
    }
}
