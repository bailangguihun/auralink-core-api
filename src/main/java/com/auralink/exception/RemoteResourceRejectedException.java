package com.auralink.exception;

import java.io.IOException;

/**
 * Raised when a remote resource request violates the server-side fetch policy.
 */
public class RemoteResourceRejectedException extends IOException {

    public RemoteResourceRejectedException(String message) {
        super(message);
    }

    public RemoteResourceRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
