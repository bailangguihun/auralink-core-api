package com.auralink.workflow.capability;

import java.util.Map;

/** Strict provider-parameter schema. Version 1 intentionally has no fields. */
public record WorkflowParameterSchema(
        String type,
        Map<String, WorkflowParameterDefinition> properties,
        boolean additionalProperties) {

    public WorkflowParameterSchema {
        properties = Map.copyOf(properties);
    }

    public static WorkflowParameterSchema emptyStrictObject() {
        return new WorkflowParameterSchema("object", Map.of(), false);
    }

    /** Reserved typed value for reviewed parameter definitions in a later schema. */
    public record WorkflowParameterDefinition(String type) {
    }
}
