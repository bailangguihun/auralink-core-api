package com.auralink.creation;

/** Durable boundary marker for a provider submission; no provider is sent in ROUND 9B.1. */
public enum ProviderDispatchState {
    NOT_SENT,
    SEND_STARTED,
    RESULT_PERSISTED
}
