package com.auralink.creation.provider;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/** Exact operation/provider/modality binding advertised by one adapter. */
public record ProviderAdapterBinding(
        WorkflowOperation operation,
        String providerCode,
        WorkflowModality inputModality,
        WorkflowModality outputModality) {

    public ProviderAdapterBinding {
        if (operation == null || providerCode == null || providerCode.isBlank()
                || inputModality == null || outputModality == null) {
            throw new IllegalArgumentException("Complete provider adapter binding is required");
        }
    }
}
