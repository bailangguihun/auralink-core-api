package com.auralink.provider.seedream;

import com.auralink.provider.artifact.ProviderArtifact;

/** Converts one transient provider URL into a validated contained artifact. */
@FunctionalInterface
public interface SeedreamResultFetcher {
    default void prepare() {
        // Alternative internal implementations may have no filesystem preflight.
    }

    ProviderArtifact fetch(String resultUrl);
}
