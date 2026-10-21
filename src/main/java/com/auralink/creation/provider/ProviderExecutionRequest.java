package com.auralink.creation.provider;

import com.auralink.workflow.WorkflowOperation;
import com.auralink.provider.vmm.VmmExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.regex.Pattern;

public record ProviderExecutionRequest(String requestId, WorkflowOperation operation, String providerCode, ProviderInput input, Map<String, JsonNode> parameters, VmmExecutionContext vmmContext) {
   private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

   public ProviderExecutionRequest(String requestId, WorkflowOperation operation, String providerCode, ProviderInput input, Map<String, JsonNode> parameters, VmmExecutionContext vmmContext) {
      if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
         throw new IllegalArgumentException("Safe provider request ID is required");
      }

      if (operation != null && providerCode != null && !providerCode.isBlank() && input != null) {
         parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
         this.requestId = requestId;
         this.operation = operation;
         this.providerCode = providerCode;
         this.input = input;
         this.parameters = parameters;
         this.vmmContext = vmmContext;
      } else {
         throw new IllegalArgumentException("Provider operation, code, and input are required");
      }
   }

   public ProviderExecutionRequest(String requestId, WorkflowOperation operation, String providerCode, ProviderInput input, Map<String, JsonNode> parameters) {
      this(requestId, operation, providerCode, input, parameters, null);
   }

   public ProviderExecutionRequest(String requestId, WorkflowOperation operation, String providerCode, ProviderInput input) {
      this(requestId, operation, providerCode, input, Map.of());
   }
}
