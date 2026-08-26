package com.auralink.workflow.graph;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.WorkflowOperation;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/** Immutable public node representation used for storage and snapshots. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id", "kind", "operation", "providerCode",
        "inputModality", "outputModality", "parameters"
})
public record CanonicalWorkflowNode(
        String id,
        WorkflowNodeKind kind,
        WorkflowOperation operation,
        String providerCode,
        WorkflowModality inputModality,
        WorkflowModality outputModality,
        Map<String, JsonNode> parameters) {

    public CanonicalWorkflowNode {
        if (parameters != null) {
            parameters = Collections.unmodifiableMap(new TreeMap<>(parameters));
        }
    }

    public static CanonicalWorkflowNode source(String id, WorkflowModality outputModality) {
        return new CanonicalWorkflowNode(
                id, WorkflowNodeKind.SOURCE, null, null, null, outputModality, null);
    }

    public static CanonicalWorkflowNode transform(
            String id,
            WorkflowOperation operation,
            String providerCode,
            WorkflowModality inputModality,
            WorkflowModality outputModality) {
        return new CanonicalWorkflowNode(
                id,
                WorkflowNodeKind.TRANSFORM,
                operation,
                providerCode,
                inputModality,
                outputModality,
                Map.of());
    }
}
