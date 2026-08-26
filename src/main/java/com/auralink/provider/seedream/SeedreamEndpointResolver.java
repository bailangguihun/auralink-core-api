package com.auralink.provider.seedream;

import java.net.URI;

/** Injectable resolver permits loopback fixtures without weakening production policy. */
@FunctionalInterface
public interface SeedreamEndpointResolver {
    URI resolveGenerationEndpoint();
}
