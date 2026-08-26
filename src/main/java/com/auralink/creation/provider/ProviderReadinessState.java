package com.auralink.creation.provider;

/** Non-secret adapter implementation and controlled-execution readiness states. */
public enum ProviderReadinessState {
    ADAPTER_IMPLEMENTED,
    FEATURE_DISABLED,
    CONFIGURATION_MISSING,
    CONFIGURATION_INVALID,
    INTERNAL_SERVICE_NOT_VALIDATED,
    READY_FOR_CONTROLLED_EXECUTION,
    RESERVED_DISABLED
}
