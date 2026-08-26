package com.auralink.provider.vmm;

import java.net.URI;
import java.nio.file.Path;

/** Validated internal VMM request and output-root boundary. */
public interface VmmEndpointResolver {
    URI resolveGenerationEndpoint();
    Path resolveOutputRoot();
}
