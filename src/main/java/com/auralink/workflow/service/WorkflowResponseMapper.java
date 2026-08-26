package com.auralink.workflow.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.auralink.api.v1.workflow.WorkflowDetailResponse;
import com.auralink.api.v1.workflow.WorkflowSummaryResponse;
import com.auralink.api.v1.workflow.WorkflowTimestampFormatter;
import com.auralink.entity.UserWorkflow;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.WorkflowGraphCodec;

import lombok.RequiredArgsConstructor;

/** Maps persisted canonical definitions into public DTOs without entity leakage. */
@Component
@RequiredArgsConstructor
public class WorkflowResponseMapper {

    private final WorkflowGraphCodec codec;

    public WorkflowDetailResponse detail(UserWorkflow entity) {
        StoredWorkflowDefinition stored = parse(entity);
        return new WorkflowDetailResponse(
                entity.getPublicId(),
                entity.getName(),
                entity.getDescription(),
                entity.getSchemaVersion(),
                stored.graph(),
                stored.sourceModality(),
                stored.terminalModality(),
                stored.graph().nodes().size(),
                stored.graph().edges().size(),
                stored.operationSequence(),
                WorkflowTimestampFormatter.format(entity.getCreatedAt()),
                WorkflowTimestampFormatter.format(entity.getUpdatedAt()));
    }

    public WorkflowSummaryResponse summary(UserWorkflow entity) {
        StoredWorkflowDefinition stored = parse(entity);
        return new WorkflowSummaryResponse(
                entity.getPublicId(),
                entity.getName(),
                entity.getDescription(),
                entity.getSchemaVersion(),
                stored.sourceModality(),
                stored.terminalModality(),
                stored.graph().nodes().size(),
                WorkflowTimestampFormatter.format(entity.getUpdatedAt()),
                WorkflowTimestampFormatter.format(entity.getCreatedAt()));
    }

    public StoredWorkflowDefinition parse(UserWorkflow entity) {
        CanonicalWorkflowGraph graph = codec.decode(entity.getGraphJson());
        if (graph.nodes().isEmpty()
                || graph.nodes().get(0).kind() != WorkflowNodeKind.SOURCE) {
            throw new IllegalStateException("Persisted workflow graph is not canonical");
        }
        List<WorkflowOperation> operations = graph.nodes().stream()
                .filter(node -> node.kind() == WorkflowNodeKind.TRANSFORM)
                .map(node -> node.operation())
                .toList();
        return new StoredWorkflowDefinition(
                graph,
                graph.nodes().get(0).outputModality(),
                graph.nodes().get(graph.nodes().size() - 1).outputModality(),
                operations);
    }
}
