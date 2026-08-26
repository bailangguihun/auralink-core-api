package com.auralink.workflow;

import java.util.ArrayList;
import java.util.List;

import com.auralink.api.v1.workflow.WorkflowDefinitionRequest;
import com.auralink.workflow.graph.WorkflowEdgeRequest;
import com.auralink.workflow.graph.WorkflowGraphRequest;
import com.auralink.workflow.graph.WorkflowNodeRequest;
import com.auralink.workflow.graph.WorkflowParameters;

/** Typed test fixtures for schema version 1 definitions. */
public final class WorkflowTestDefinitions {

    private WorkflowTestDefinitions() {
    }

    public static WorkflowDefinitionRequest definition(
            WorkflowModality sourceModality,
            WorkflowOperation... operations) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setName("  Test workflow  ");
        request.setDescription("  Test description  ");

        WorkflowGraphRequest graph = new WorkflowGraphRequest();
        graph.setSchemaVersion(1);
        List<WorkflowNodeRequest> nodes = new ArrayList<>();
        List<WorkflowEdgeRequest> edges = new ArrayList<>();

        WorkflowNodeRequest source = new WorkflowNodeRequest();
        source.setId("source");
        source.setKind(WorkflowNodeKind.SOURCE.name());
        source.setOutputModality(sourceModality.name());
        nodes.add(source);

        String previous = source.getId();
        for (int index = 0; index < operations.length; index++) {
            WorkflowNodeRequest transform = transform("step" + (index + 1), operations[index]);
            nodes.add(transform);
            WorkflowEdgeRequest edge = new WorkflowEdgeRequest();
            edge.setFrom(previous);
            edge.setTo(transform.getId());
            edges.add(edge);
            previous = transform.getId();
        }
        graph.setNodes(nodes);
        graph.setEdges(edges);
        request.setGraph(graph);
        return request;
    }

    public static WorkflowNodeRequest transform(String id, WorkflowOperation operation) {
        WorkflowNodeRequest node = new WorkflowNodeRequest();
        node.setId(id);
        node.setKind(WorkflowNodeKind.TRANSFORM.name());
        node.setOperation(operation.name());
        node.setProviderCode(provider(operation));
        node.setInputModality(input(operation).name());
        node.setOutputModality(output(operation).name());
        node.setParameters(new WorkflowParameters());
        return node;
    }

    public static String provider(WorkflowOperation operation) {
        return switch (operation) {
            case TEXT_TO_PAINTING, IMAGE_TO_PAINTING -> "seedream-5";
            case POEM_TO_PAINTING -> "qwen3vl-seedream5";
            case PAINTING_TO_MUSIC -> "auralink-vmm";
            case PAINTING_TO_POEM -> "qwen3-vl-plus";
            case PAINTING_TO_VIDEO -> "reserved-video";
        };
    }

    public static WorkflowModality input(WorkflowOperation operation) {
        return switch (operation) {
            case TEXT_TO_PAINTING -> WorkflowModality.TEXT_DESCRIPTION;
            case POEM_TO_PAINTING -> WorkflowModality.POEM;
            case IMAGE_TO_PAINTING -> WorkflowModality.IMAGE;
            case PAINTING_TO_MUSIC, PAINTING_TO_POEM, PAINTING_TO_VIDEO ->
                    WorkflowModality.PAINTING;
        };
    }

    public static WorkflowModality output(WorkflowOperation operation) {
        return switch (operation) {
            case TEXT_TO_PAINTING, POEM_TO_PAINTING, IMAGE_TO_PAINTING ->
                    WorkflowModality.PAINTING;
            case PAINTING_TO_MUSIC -> WorkflowModality.AUDIO;
            case PAINTING_TO_POEM -> WorkflowModality.POEM;
            case PAINTING_TO_VIDEO -> WorkflowModality.VIDEO;
        };
    }
}
