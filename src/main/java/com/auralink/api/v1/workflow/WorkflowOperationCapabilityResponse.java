package com.auralink.api.v1.workflow;

import java.util.List;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import com.auralink.creation.CreationExecutionCapabilityService.ExecutionAvailability;

/** Public definition-time operation catalog entry. */
public record WorkflowOperationCapabilityResponse(
        WorkflowOperation code,
        String displayName,
        WorkflowModality inputModality,
        WorkflowModality outputModality,
        boolean definitionEnabled,
        boolean executionAvailable,
        boolean terminalOutput,
        String availabilityReason,
        List<WorkflowProviderCapabilityResponse> providers) {

    public WorkflowOperationCapabilityResponse {
        providers = List.copyOf(providers);
    }

    static WorkflowOperationCapabilityResponse from(
            WorkflowOperationCapability capability,
            ExecutionAvailability execution) {
        return new WorkflowOperationCapabilityResponse(
                capability.operation(),
                capability.displayName(),
                capability.inputModality(),
                capability.outputModality(),
                capability.definitionEnabled(),
                execution.available(),
                capability.terminalOutput(),
                execution.reason(),
                capability.providers().stream()
                        .map(provider -> WorkflowProviderCapabilityResponse.from(
                                provider, execution.available()))
                        .toList());
    }
}
