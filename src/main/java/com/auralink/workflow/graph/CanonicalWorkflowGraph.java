package com.auralink.workflow.graph;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Immutable, source-to-terminal ordered graph persisted as one JSON value. */
@JsonPropertyOrder({"schemaVersion", "nodes", "edges"})
public record CanonicalWorkflowGraph(
        int schemaVersion,
        List<CanonicalWorkflowNode> nodes,
        List<CanonicalWorkflowEdge> edges) {

    public CanonicalWorkflowGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
