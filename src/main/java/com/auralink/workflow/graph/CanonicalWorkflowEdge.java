package com.auralink.workflow.graph;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Immutable canonical edge. */
@JsonPropertyOrder({"from", "to"})
public record CanonicalWorkflowEdge(String from, String to) {
}
