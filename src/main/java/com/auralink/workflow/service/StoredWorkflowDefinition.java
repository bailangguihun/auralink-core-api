package com.auralink.workflow.service;

import java.util.List;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;

/** Parsed immutable view of a persisted canonical graph. */
public record StoredWorkflowDefinition(
        CanonicalWorkflowGraph graph,
        WorkflowModality sourceModality,
        WorkflowModality terminalModality,
        List<WorkflowOperation> operationSequence) {

    public StoredWorkflowDefinition {
        operationSequence = List.copyOf(operationSequence);
    }
}
