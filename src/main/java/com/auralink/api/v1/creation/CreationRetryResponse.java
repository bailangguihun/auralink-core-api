package com.auralink.api.v1.creation;

import com.auralink.creation.CreationStatus;

/** Safe retry admission acknowledgement with no provider or storage internals. */
public record CreationRetryResponse(
        String creationId,
        CreationStatus status,
        int retryVersion,
        int executionAttemptNumber,
        String acceptedAt,
        boolean idempotentReplay) {
}
