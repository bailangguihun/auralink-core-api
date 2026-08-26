package com.auralink.api.v1.workflow;

import java.util.List;

import com.auralink.workflow.WorkflowModality;
import com.auralink.creation.CreationRuntimeCapabilityService;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;

/** Provider-independent workflow editor capability contract. */
public record WorkflowNodeTypesResponse(
        int workflowSchemaVersion,
        boolean featureEnabled,
        List<WorkflowModality> sourceModalities,
        List<WorkflowOperationCapabilityResponse> operations) {

    public WorkflowNodeTypesResponse {
        sourceModalities = List.copyOf(sourceModalities);
        operations = List.copyOf(operations);
    }

    static WorkflowNodeTypesResponse from(
            int schemaVersion,
            boolean featureEnabled,
            WorkflowCapabilityRegistry registry,
            CreationRuntimeCapabilityService creationCapabilities) {
        return new WorkflowNodeTypesResponse(
                schemaVersion,
                featureEnabled,
                registry.sourceModalities(),
                registry.operations().stream()
                        .map(capability -> WorkflowOperationCapabilityResponse.from(
                                capability, creationCapabilities.availability(capability)))
                        .toList());
    }
}
