package com.auralink.ops.round51;

/** Minimal CLI result. Database and filesystem implementation details stay private. */
public record Round51ActivationResult(
        Round51ActivationState state,
        String sourceFingerprint,
        int paintings,
        int catalogMediaAssets) {
}
