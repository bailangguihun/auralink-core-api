package com.auralink.api.v1.workflow;

import com.auralink.creation.CreationExecutionCapabilityService;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.capability.WorkflowOperationCapability;
import java.util.List;

public record WorkflowOperationCapabilityResponse(
   WorkflowOperation code,
   String displayName,
   WorkflowModality inputModality,
   WorkflowModality outputModality,
   boolean definitionEnabled,
   boolean draftEnabled,
   boolean executionAvailable,
   boolean terminalOutput,
   String availabilityReason,
   String executionConstraint,
   List<WorkflowProviderCapabilityResponse> providers
) {
   public WorkflowOperationCapabilityResponse {
      providers = List.copyOf(providers);
   }

   static WorkflowOperationCapabilityResponse from(WorkflowOperationCapability capability, CreationExecutionCapabilityService.ExecutionAvailability execution) {
      return new WorkflowOperationCapabilityResponse(
         capability.operation(),
         capability.displayName(),
         capability.inputModality(),
         capability.outputModality(),
         capability.definitionEnabled(),
         capability.draftEnabled(),
         execution.available(),
         capability.terminalOutput(),
         execution.reason(),
         capability.operation() == WorkflowOperation.PAINTING_TO_MUSIC ? "CATALOG_OR_OWNED_UPLOAD_PAINTING_SINGLE_TRANSFORM_ONLY" : null,
         capability.providers().stream().map(provider -> WorkflowProviderCapabilityResponse.from(provider, execution.available())).toList()
      );
   }
}
