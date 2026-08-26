package com.auralink.ops.round51;

/** Mutually exclusive database states accepted by the one-time activation tool. */
public enum Round51ActivationState {
    INHERITED_READY,
    ACTIVATED_NOW,
    ALREADY_ACTIVATED_HEALTHY,
    PARTIALLY_ACTIVATED_UNKNOWN
}
