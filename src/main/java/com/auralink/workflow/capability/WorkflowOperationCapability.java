package com.auralink.workflow.capability;

import java.util.List;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/** One authoritative definition-time operation rule. */
public record WorkflowOperationCapability(
        WorkflowOperation operation,
        String displayName,
        WorkflowModality inputModality,
        WorkflowModality outputModality,
        boolean definitionEnabled,
        boolean executionAvailable,
        boolean terminalOutput,
        String availabilityReason,
        List<WorkflowProviderCapability> providers) {

    public WorkflowOperationCapability {
        providers = List.copyOf(providers);
    }

    public boolean allowsProvider(String providerCode) {
        return providers.stream()
                .anyMatch(provider -> provider.definitionEnabled()
                        && provider.code().equals(providerCode));
    }
}
