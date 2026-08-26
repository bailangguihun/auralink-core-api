package com.auralink.creation.provider;

import com.auralink.provider.artifact.ProviderArtifact;
import com.auralink.workflow.WorkflowModality;

/** Replayable validated image source backed only by controlled transient staging. */
public record ProviderImageInput(
        ProviderArtifact artifact,
        WorkflowModality modality,
        PaintingMetadataContext paintingMetadata) implements ProviderInput {

    public ProviderImageInput {
        if (artifact == null || modality == null) {
            throw new IllegalArgumentException("Provider image artifact and modality are required");
        }
        if (modality != WorkflowModality.IMAGE && modality != WorkflowModality.PAINTING) {
            throw new IllegalArgumentException("Provider image modality must be IMAGE or PAINTING");
        }
        if (modality != WorkflowModality.PAINTING && paintingMetadata != null) {
            throw new IllegalArgumentException("Painting metadata is valid only for PAINTING input");
        }
    }
}
