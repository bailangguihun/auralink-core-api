package com.auralink.workflow.capability;

/** Public-safe provider choice metadata; it contains no runtime configuration. */
public record WorkflowProviderCapability(
        String code,
        String displayName,
        boolean definitionEnabled,
        boolean executionAvailable,
        WorkflowParameterSchema parameterSchema) {
}
