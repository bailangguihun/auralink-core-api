package com.auralink.workflow.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.auralink.api.v1.error.ApiErrorCode;
import com.auralink.api.v1.error.ApiV1Exception;
import com.auralink.api.v1.workflow.WorkflowDefinitionRequest;
import com.auralink.entity.User;
import com.auralink.entity.UserWorkflow;
import com.auralink.repository.UserWorkflowRepository;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.WorkflowOperation;
import com.auralink.workflow.graph.CanonicalWorkflowGraph;
import com.auralink.workflow.graph.CanonicalWorkflowNode;
import com.auralink.workflow.graph.WorkflowEdgeRequest;
import com.auralink.workflow.graph.WorkflowGraphCodec;
import com.auralink.workflow.graph.WorkflowGraphRequest;
import com.auralink.workflow.graph.WorkflowNodeRequest;
import com.auralink.workflow.graph.WorkflowParameters;
import com.auralink.workflow.graph.WorkflowValidationResult;
import com.auralink.workflow.graph.WorkflowValidator;
import com.auralink.workflow.snapshot.WorkflowSnapshot;
import com.auralink.workflow.snapshot.WorkflowSnapshotFactory;

import lombok.RequiredArgsConstructor;

/**
 * Revalidates a stored owner workflow and produces the only detached snapshot
 * future Creation execution may read.  It never resolves user source data or
 * contacts a provider.
 */
@Service
@RequiredArgsConstructor
public class WorkflowExecutionPreparer {

    private final UserWorkflowRepository workflows;
    private final WorkflowGraphCodec graphCodec;
    private final WorkflowValidator validator;
    private final WorkflowSnapshotFactory snapshotFactory;

    public PreparedWorkflow prepare(String workflowId, User owner) {
        String canonicalWorkflowId = canonicalWorkflowId(workflowId);
        UserWorkflow workflow = workflows.findByPublicIdAndUser_IdAndStatus(
                        canonicalWorkflowId, owner.getId(), UserWorkflowService.ACTIVE_STATUS)
                .orElseThrow(WorkflowExecutionPreparer::workflowNotFound);

        CanonicalWorkflowGraph storedGraph;
        try {
            storedGraph = graphCodec.decode(workflow.getGraphJson());
        } catch (RuntimeException exception) {
            throw workflowInvalid();
        }

        WorkflowValidationResult validation = validator.validate(toRequest(workflow, storedGraph));
        if (!validation.valid()
                || validation.canonicalization() == null
                || !workflow.getGraphJson().equals(validation.canonicalization().canonicalJson())) {
            throw workflowInvalid();
        }

        var snapshotResult = snapshotFactory.create(
                workflow.getPublicId(),
                workflow.getName(),
                workflow.getSchemaVersion(),
                validation.canonicalization().graph());
        List<PreparedTransform> transforms = validation.canonicalization().graph().nodes().stream()
                .filter(node -> node.kind() == WorkflowNodeKind.TRANSFORM)
                .map(this::toTransform)
                .toList();
        if (transforms.isEmpty()) {
            throw workflowInvalid();
        }
        return new PreparedWorkflow(
                workflow,
                snapshotResult.snapshot(),
                snapshotResult.canonicalJson(),
                validation.canonicalization().sourceModality(),
                validation.canonicalization().terminalModality(),
                transforms);
    }

    private PreparedTransform toTransform(CanonicalWorkflowNode node) {
        if (node.operation() == null || node.providerCode() == null
                || node.inputModality() == null || node.outputModality() == null) {
            throw workflowInvalid();
        }
        String parametersJson;
        try {
            parametersJson = graphCodec.canonicalMapperCopy().writeValueAsString(node.parameters());
        } catch (Exception exception) {
            throw workflowInvalid();
        }
        return new PreparedTransform(
                node.id(),
                node.operation(),
                node.providerCode(),
                node.inputModality(),
                node.outputModality(),
                parametersJson);
    }

    private WorkflowDefinitionRequest toRequest(UserWorkflow workflow, CanonicalWorkflowGraph graph) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setName(workflow.getName());
        request.setDescription(workflow.getDescription());
        WorkflowGraphRequest requestedGraph = new WorkflowGraphRequest();
        requestedGraph.setSchemaVersion(graph.schemaVersion());
        requestedGraph.setNodes(graph.nodes().stream().map(this::toRequestNode).toList());
        requestedGraph.setEdges(graph.edges().stream().map(edge -> {
            WorkflowEdgeRequest requestedEdge = new WorkflowEdgeRequest();
            requestedEdge.setFrom(edge.from());
            requestedEdge.setTo(edge.to());
            return requestedEdge;
        }).toList());
        request.setGraph(requestedGraph);
        return request;
    }

    private WorkflowNodeRequest toRequestNode(CanonicalWorkflowNode node) {
        WorkflowNodeRequest requestedNode = new WorkflowNodeRequest();
        requestedNode.setId(node.id());
        requestedNode.setKind(node.kind().name());
        requestedNode.setOutputModality(node.outputModality().name());
        if (node.kind() == WorkflowNodeKind.TRANSFORM) {
            requestedNode.setOperation(node.operation().name());
            requestedNode.setProviderCode(node.providerCode());
            requestedNode.setInputModality(node.inputModality().name());
            WorkflowParameters parameters = new WorkflowParameters();
            if (node.parameters() != null) {
                node.parameters().forEach(parameters::put);
            }
            requestedNode.setParameters(parameters);
        }
        return requestedNode;
    }

    private String canonicalWorkflowId(String workflowId) {
        try {
            String canonical = UUID.fromString(workflowId).toString();
            if (!canonical.equals(workflowId)) {
                throw workflowNotFound();
            }
            return canonical;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw workflowNotFound();
        }
    }

    private static ApiV1Exception workflowNotFound() {
        return new ApiV1Exception(HttpStatus.NOT_FOUND, ApiErrorCode.WORKFLOW_NOT_FOUND, "工作流不存在");
    }

    private static ApiV1Exception workflowInvalid() {
        return new ApiV1Exception(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.WORKFLOW_INVALID,
                "工作流定义验证失败");
    }

    public record PreparedWorkflow(
            UserWorkflow workflow,
            WorkflowSnapshot snapshot,
            String snapshotJson,
            WorkflowModality sourceModality,
            WorkflowModality terminalModality,
            List<PreparedTransform> transforms) {

        public PreparedWorkflow {
            transforms = List.copyOf(transforms);
        }
    }

    public record PreparedTransform(
            String nodeId,
            WorkflowOperation operation,
            String providerCode,
            WorkflowModality inputModality,
            WorkflowModality outputModality,
            String parametersJson) {
    }
}
