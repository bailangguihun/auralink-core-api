package com.auralink.workflow.graph;

import java.util.List;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;

/** Complete immutable result of canonicalizing one valid graph. */
public record WorkflowCanonicalization(
        CanonicalWorkflowGraph graph,
        String canonicalJson,
        WorkflowModality sourceModality,
        WorkflowModality terminalModality,
        List<WorkflowOperation> operationSequence) {

    public WorkflowCanonicalization {
        operationSequence = List.copyOf(operationSequence);
    }

    public int nodeCount() {
        return graph.nodes().size();
    }

    public int edgeCount() {
        return graph.edges().size();
    }
}
