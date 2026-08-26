package com.auralink.api.v1.workflow;

import com.auralink.workflow.capability.WorkflowParameterSchema;
import com.auralink.workflow.capability.WorkflowProviderCapability;

/** Public-safe provider choice for one definition operation. */
public record WorkflowProviderCapabilityResponse(
        String code,
        String displayName,
        boolean definitionEnabled,
        boolean executionAvailable,
        WorkflowParameterSchema parameterSchema) {

    static WorkflowProviderCapabilityResponse from(
            WorkflowProviderCapability provider,
            boolean executionAvailable) {
        return new WorkflowProviderCapabilityResponse(
                provider.code(),
                provider.displayName(),
                provider.definitionEnabled(),
                executionAvailable,
                provider.parameterSchema());
    }
}
