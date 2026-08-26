package com.auralink.api.v1.creation;

import com.auralink.creation.CreationStatus;

/** Immediate asynchronous-admission acknowledgement; no execution is started in ROUND 9B.1. */
public record CreationQueuedResponse(String creationId, CreationStatus status) {
}
