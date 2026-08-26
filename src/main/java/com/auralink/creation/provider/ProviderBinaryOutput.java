package com.auralink.creation.provider;

import com.auralink.provider.artifact.ProviderArtifact;

/** Safe binary metadata plus its single transient artifact owner. */
public record ProviderBinaryOutput(
        ProviderArtifact artifact,
        String mimeType,
        long byteLength,
        String sha256,
        Integer width,
        Integer height) implements ProviderOutput {

    public ProviderBinaryOutput(ProviderArtifact artifact) {
        this(
                artifact,
                artifact.mimeType(),
                artifact.byteLength(),
                artifact.sha256(),
                artifact.width(),
                artifact.height());
    }

    public ProviderBinaryOutput {
        if (artifact == null || mimeType == null || sha256 == null || byteLength < 1) {
            throw new IllegalArgumentException("Valid provider binary output is required");
        }
    }
}
