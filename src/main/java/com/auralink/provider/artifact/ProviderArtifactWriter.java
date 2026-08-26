package com.auralink.provider.artifact;

import java.nio.file.Path;

/** Trusted internal writer for one randomly allocated staging target. */
@FunctionalInterface
public interface ProviderArtifactWriter {
    void write(Path controlledTarget) throws Exception;
}
