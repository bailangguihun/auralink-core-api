package com.auralink.creation;

/** Safe owner-visible recovery projection; no lease or provider internals are exposed. */
public enum CreationRecoveryState {
    NONE,
    PROVIDER_DISPATCH_AMBIGUOUS,
    OPERATOR_REVIEW_REQUIRED
}
