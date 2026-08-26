package com.auralink.workflow.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.WorkflowOperation;

import lombok.RequiredArgsConstructor;

/** Source-to-terminal normalization for a graph already accepted by the validator. */
@Component
@RequiredArgsConstructor
public class WorkflowCanonicalizer {

    private final WorkflowGraphCodec codec;

    public WorkflowCanonicalization canonicalize(WorkflowGraphRequest request) {
        Map<String, WorkflowNodeRequest> nodesById = new LinkedHashMap<>();
        request.getNodes().forEach(node -> nodesById.put(node.getId(), node));
        Map<String, String> nextById = new LinkedHashMap<>();
        request.getEdges().forEach(edge -> nextById.put(edge.getFrom(), edge.getTo()));

        WorkflowNodeRequest current = request.getNodes().stream()
                .filter(node -> WorkflowNodeKind.SOURCE.name().equals(node.getKind()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Valid graph has no source"));

        List<CanonicalWorkflowNode> orderedNodes = new ArrayList<>();
        List<CanonicalWorkflowEdge> orderedEdges = new ArrayList<>();
        List<WorkflowOperation> operations = new ArrayList<>();
        WorkflowModality sourceModality = WorkflowModality.valueOf(current.getOutputModality());
        WorkflowModality terminalModality = sourceModality;

        while (current != null) {
            CanonicalWorkflowNode canonicalNode;
            if (WorkflowNodeKind.SOURCE.name().equals(current.getKind())) {
                canonicalNode = CanonicalWorkflowNode.source(
                        current.getId(), WorkflowModality.valueOf(current.getOutputModality()));
            } else {
                WorkflowOperation operation = WorkflowOperation.valueOf(current.getOperation());
                operations.add(operation);
                canonicalNode = CanonicalWorkflowNode.transform(
                        current.getId(),
                        operation,
                        current.getProviderCode(),
                        WorkflowModality.valueOf(current.getInputModality()),
                        WorkflowModality.valueOf(current.getOutputModality()));
            }
            orderedNodes.add(canonicalNode);
            terminalModality = canonicalNode.outputModality();

            String nextId = nextById.get(current.getId());
            if (nextId == null) {
                current = null;
            } else {
                orderedEdges.add(new CanonicalWorkflowEdge(current.getId(), nextId));
                current = nodesById.get(nextId);
            }
        }

        CanonicalWorkflowGraph graph = new CanonicalWorkflowGraph(
                request.getSchemaVersion(), orderedNodes, orderedEdges);
        return new WorkflowCanonicalization(
                graph,
                codec.encode(graph),
                sourceModality,
                terminalModality,
                operations);
    }
}
