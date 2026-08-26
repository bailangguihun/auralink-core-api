package com.auralink.creation.provider;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/** Provider-neutral result containing only ROUND 9 handoff data. */
public record ProviderExecutionResult(
        String requestId,
        WorkflowOperation operation,
        String providerCode,
        WorkflowModality outputModality,
        ProviderOutput output) {

    public ProviderExecutionResult {
        if (requestId == null || operation == null || providerCode == null
                || outputModality == null || output == null) {
            throw new IllegalArgumentException("Complete provider execution result is required");
        }
    }
}
