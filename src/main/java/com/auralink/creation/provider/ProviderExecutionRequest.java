package com.auralink.creation.provider;

import java.util.regex.Pattern;

import com.auralink.workflow.WorkflowOperation;

/** One bounded transform request with no entity, credential, endpoint, or path. */
public record ProviderExecutionRequest(
        String requestId,
        WorkflowOperation operation,
        String providerCode,
        ProviderInput input) {

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    public ProviderExecutionRequest {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new IllegalArgumentException("Safe provider request ID is required");
        }
        if (operation == null || providerCode == null || providerCode.isBlank() || input == null) {
            throw new IllegalArgumentException("Provider operation, code, and input are required");
        }
    }
}
