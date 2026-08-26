package com.auralink.workflow.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.auralink.api.v1.error.ApiViolationDetail;
import com.auralink.api.v1.workflow.WorkflowDefinitionRequest;
import com.auralink.config.properties.WorkflowProperties;
import com.auralink.workflow.WorkflowModality;
import com.auralink.workflow.WorkflowNodeKind;
import com.auralink.workflow.capability.WorkflowCapabilityRegistry;
import com.auralink.workflow.capability.WorkflowOperationCapability;

import lombok.RequiredArgsConstructor;

/** Authoritative business and topology validator for workflow definitions. */
@Component
@RequiredArgsConstructor
public class WorkflowValidator {

    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Comparator<ApiViolationDetail> VIOLATION_ORDER = Comparator
            .comparing(ApiViolationDetail::path, Comparator.nullsFirst(String::compareTo))
            .thenComparing(ApiViolationDetail::code)
            .thenComparing(ApiViolationDetail::nodeId, Comparator.nullsFirst(String::compareTo))
            .thenComparing(ApiViolationDetail::edgeIndex, Comparator.nullsFirst(Integer::compareTo))
            .thenComparing(ApiViolationDetail::message);

    private final WorkflowProperties properties;
    private final WorkflowCapabilityRegistry registry;
    private final WorkflowGraphCodec codec;
    private final WorkflowCanonicalizer canonicalizer;

    public WorkflowValidationResult validate(WorkflowDefinitionRequest request) {
        List<ApiViolationDetail> violations = new ArrayList<>();
        if (request == null) {
            add(violations, WorkflowViolationCode.GRAPH_REQUIRED, "$", null, null,
                    "Workflow definition is required");
            return result(null, null, null, violations);
        }

        request.unknownFields().keySet().forEach(field -> add(
                violations,
                WorkflowViolationCode.UNKNOWN_FIELD,
                "$." + field,
                null,
                null,
                "Unknown workflow definition field"));

        String normalizedName = normalizeRequiredName(request.getName(), violations);
        String normalizedDescription = normalizeDescription(request.getDescription(), violations);
        WorkflowGraphRequest graph = request.getGraph();
        if (graph == null) {
            add(violations, WorkflowViolationCode.GRAPH_REQUIRED, "$.graph", null, null,
                    "Workflow graph is required");
            return result(normalizedName, normalizedDescription, null, violations);
        }

        validateGraph(graph, violations);
        if (!violations.isEmpty()) {
            return result(normalizedName, normalizedDescription, null, violations);
        }

        WorkflowCanonicalization canonicalization = canonicalizer.canonicalize(graph);
        return new WorkflowValidationResult(
                true,
                normalizedName,
                normalizedDescription,
                canonicalization,
                List.of());
    }

    private void validateGraph(
            WorkflowGraphRequest graph,
            List<ApiViolationDetail> violations) {
        graph.unknownFields().keySet().forEach(field -> add(
                violations,
                WorkflowViolationCode.UNKNOWN_FIELD,
                "$.graph." + field,
                null,
                null,
                "Unknown graph field"));

        if (!Objects.equals(graph.getSchemaVersion(), properties.getSchemaVersion())) {
            add(violations,
                    WorkflowViolationCode.WORKFLOW_SCHEMA_UNSUPPORTED,
                    "$.graph.schemaVersion",
                    null,
                    null,
                    "Workflow schema version is unsupported");
        }
        try {
            if (codec.requestSizeBytes(graph) > properties.getMaxGraphBytes()) {
                add(violations,
                        WorkflowViolationCode.WORKFLOW_GRAPH_TOO_LARGE,
                        "$.graph",
                        null,
                        null,
                        "Workflow graph exceeds the configured byte limit");
            }
        } catch (IllegalArgumentException exception) {
            add(violations,
                    WorkflowViolationCode.WORKFLOW_GRAPH_TOO_LARGE,
                    "$.graph",
                    null,
                    null,
                    "Workflow graph cannot be measured safely");
        }

        List<WorkflowNodeRequest> nodes = graph.getNodes() == null ? List.of() : graph.getNodes();
        List<WorkflowEdgeRequest> edges = graph.getEdges() == null ? List.of() : graph.getEdges();
        if (nodes.size() < 2 || nodes.size() > properties.getMaxNodes()) {
            add(violations,
                    WorkflowViolationCode.NODE_COUNT_INVALID,
                    "$.graph.nodes",
                    null,
                    null,
                    "Workflow node count must be between 2 and " + properties.getMaxNodes());
        }
        if (nodes.size() == 1
                && nodes.get(0) != null
                && WorkflowNodeKind.SOURCE.name().equals(nodes.get(0).getKind())) {
            add(violations,
                    WorkflowViolationCode.SOURCE_ONLY_WORKFLOW,
                    "$.graph.nodes",
                    nodes.get(0).getId(),
                    null,
                    "A source-only workflow is not valid");
        }
        if (edges.isEmpty() || edges.size() > properties.getMaxEdges()) {
            add(violations,
                    WorkflowViolationCode.EDGE_COUNT_INVALID,
                    "$.graph.edges",
                    null,
                    null,
                    "Workflow edge count must be between 1 and " + properties.getMaxEdges());
        }

        Map<String, WorkflowNodeRequest> nodesById = new LinkedHashMap<>();
        List<WorkflowNodeRequest> sourceNodes = new ArrayList<>();
        Map<String, WorkflowOperationCapability> capabilitiesByNode = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            WorkflowNodeRequest node = nodes.get(index);
            String path = "$.graph.nodes[" + index + "]";
            if (node == null) {
                add(violations, WorkflowViolationCode.NODE_REQUIRED, path, null, null,
                        "Workflow node must be an object");
                continue;
            }
            node.unknownFields().keySet().forEach(field -> add(
                    violations,
                    WorkflowViolationCode.UNKNOWN_FIELD,
                    path + "." + field,
                    node.getId(),
                    null,
                    "Unknown node field"));
            validateNodeId(node, path, nodesById, violations);

            if (WorkflowNodeKind.SOURCE.name().equals(node.getKind())) {
                sourceNodes.add(node);
                validateSourceNode(node, path, violations);
            } else if (WorkflowNodeKind.TRANSFORM.name().equals(node.getKind())) {
                validateTransformNode(node, path, capabilitiesByNode, violations);
            } else {
                add(violations,
                        WorkflowViolationCode.NODE_KIND_INVALID,
                        path + ".kind",
                        node.getId(),
                        null,
                        "Node kind must be SOURCE or TRANSFORM");
            }
        }

        if (sourceNodes.size() != 1) {
            add(violations,
                    WorkflowViolationCode.SOURCE_COUNT_INVALID,
                    "$.graph.nodes",
                    null,
                    null,
                    "Workflow must contain exactly one SOURCE node");
        }

        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Integer> outdegree = new LinkedHashMap<>();
        nodesById.keySet().forEach(id -> {
            outgoing.put(id, new ArrayList<>());
            indegree.put(id, 0);
            outdegree.put(id, 0);
        });
        Set<String> edgeKeys = new HashSet<>();
        for (int index = 0; index < edges.size(); index++) {
            validateEdge(
                    edges.get(index),
                    index,
                    nodesById,
                    edgeKeys,
                    outgoing,
                    indegree,
                    outdegree,
                    violations);
        }

        if (edges.size() != Math.max(0, nodesById.size() - 1)) {
            add(violations,
                    WorkflowViolationCode.EDGE_COUNT_CHAIN_MISMATCH,
                    "$.graph.edges",
                    null,
                    null,
                    "A single chain must have exactly nodeCount - 1 edges");
        }

        validateDegrees(
                nodesById,
                sourceNodes,
                capabilitiesByNode,
                indegree,
                outdegree,
                violations);
        validateEdgeModalities(edges, nodesById, violations);
        validateCycle(nodesById.keySet(), outgoing, indegree, violations);
        validateConnectivity(nodesById, sourceNodes, outgoing, violations);
    }

    private void validateNodeId(
            WorkflowNodeRequest node,
            String path,
            Map<String, WorkflowNodeRequest> nodesById,
            List<ApiViolationDetail> violations) {
        String id = node.getId();
        if (id == null || id.isEmpty()) {
            add(violations, WorkflowViolationCode.NODE_ID_REQUIRED, path + ".id", null, null,
                    "Node id is required");
            return;
        }
        if (!NODE_ID.matcher(id).matches()) {
            add(violations, WorkflowViolationCode.NODE_ID_INVALID, path + ".id", id, null,
                    "Node id must match [A-Za-z][A-Za-z0-9_-]{0,63}");
        }
        if (nodesById.putIfAbsent(id, node) != null) {
            add(violations, WorkflowViolationCode.DUPLICATE_NODE_ID, path + ".id", id, null,
                    "Node id must be unique");
        }
    }

    private void validateSourceNode(
            WorkflowNodeRequest node,
            String path,
            List<ApiViolationDetail> violations) {
        if (!isAllowedSourceModality(node.getOutputModality())) {
            add(violations,
                    WorkflowViolationCode.SOURCE_MODALITY_INVALID,
                    path + ".outputModality",
                    node.getId(),
                    null,
                    "SOURCE output modality is not allowed");
        }
        rejectSourceField(node.hasInputModalityField(), "inputModality", node, path, violations);
        rejectSourceField(node.hasOperationField(), "operation", node, path, violations);
        rejectSourceField(node.hasProviderCodeField(), "providerCode", node, path, violations);
        if (node.hasParametersField()) {
            add(violations,
                    WorkflowViolationCode.SOURCE_FIELD_NOT_ALLOWED,
                    path + ".parameters",
                    node.getId(),
                    null,
                    "SOURCE must not contain transform-only fields");
        }
    }

    private void rejectSourceField(
            boolean present,
            String field,
            WorkflowNodeRequest node,
            String path,
            List<ApiViolationDetail> violations) {
        if (present) {
            add(violations,
                    WorkflowViolationCode.SOURCE_FIELD_NOT_ALLOWED,
                    path + "." + field,
                    node.getId(),
                    null,
                    "SOURCE must not contain transform-only fields");
        }
    }

    private void validateTransformNode(
            WorkflowNodeRequest node,
            String path,
            Map<String, WorkflowOperationCapability> capabilitiesByNode,
            List<ApiViolationDetail> violations) {
        Optional<WorkflowOperationCapability> capability = Optional.empty();
        if (node.getOperation() == null || node.getOperation().isBlank()) {
            add(violations,
                    WorkflowViolationCode.OPERATION_REQUIRED,
                    path + ".operation",
                    node.getId(),
                    null,
                    "TRANSFORM operation is required");
        } else {
            capability = registry.find(node.getOperation());
            if (capability.isEmpty()) {
                add(violations,
                        WorkflowViolationCode.OPERATION_UNKNOWN,
                        path + ".operation",
                        node.getId(),
                        null,
                        "Workflow operation is unknown");
            } else {
                WorkflowOperationCapability rule = capability.orElseThrow();
                if (node.getId() != null) {
                    capabilitiesByNode.putIfAbsent(node.getId(), rule);
                }
                if (!rule.definitionEnabled()) {
                    add(violations,
                            WorkflowViolationCode.OPERATION_DISABLED,
                            path + ".operation",
                            node.getId(),
                            null,
                            "Workflow operation is disabled for definitions");
                }
                if (!rule.inputModality().name().equals(node.getInputModality())) {
                    add(violations,
                            WorkflowViolationCode.INPUT_MODALITY_MISMATCH,
                            path + ".inputModality",
                            node.getId(),
                            null,
                            "Declared input modality does not match operation");
                }
                if (!rule.outputModality().name().equals(node.getOutputModality())) {
                    add(violations,
                            WorkflowViolationCode.OUTPUT_MODALITY_MISMATCH,
                            path + ".outputModality",
                            node.getId(),
                            null,
                            "Declared output modality does not match operation");
                }
            }
        }

        if (node.getProviderCode() == null || node.getProviderCode().isBlank()) {
            add(violations,
                    WorkflowViolationCode.PROVIDER_REQUIRED,
                    path + ".providerCode",
                    node.getId(),
                    null,
                    "TRANSFORM providerCode is required");
        } else if (capability.isPresent()
                && !capability.orElseThrow().allowsProvider(node.getProviderCode())) {
            add(violations,
                    WorkflowViolationCode.PROVIDER_NOT_ALLOWED,
                    path + ".providerCode",
                    node.getId(),
                    null,
                    "Provider is not allowed for operation");
        }

        if (node.getParameters() == null) {
            add(violations,
                    WorkflowViolationCode.PARAMETERS_REQUIRED,
                    path + ".parameters",
                    node.getId(),
                    null,
                    "TRANSFORM parameters must be an empty object");
        } else if (!node.getParameters().isEmpty()) {
            add(violations,
                    WorkflowViolationCode.PARAMETERS_NOT_ALLOWED,
                    path + ".parameters",
                    node.getId(),
                    null,
                    "Workflow schema version 1 permits no parameters");
        }
    }

    private void validateEdge(
            WorkflowEdgeRequest edge,
            int index,
            Map<String, WorkflowNodeRequest> nodesById,
            Set<String> edgeKeys,
            Map<String, List<String>> outgoing,
            Map<String, Integer> indegree,
            Map<String, Integer> outdegree,
            List<ApiViolationDetail> violations) {
        String path = "$.graph.edges[" + index + "]";
        if (edge == null) {
            add(violations, WorkflowViolationCode.EDGE_REQUIRED, path, null, index,
                    "Workflow edge must be an object");
            return;
        }
        edge.unknownFields().keySet().forEach(field -> add(
                violations,
                WorkflowViolationCode.UNKNOWN_FIELD,
                path + "." + field,
                null,
                index,
                "Unknown edge field"));

        String from = edge.getFrom();
        String to = edge.getTo();
        if (from == null || from.isBlank()) {
            add(violations, WorkflowViolationCode.EDGE_ENDPOINT_REQUIRED, path + ".from", null, index,
                    "Edge from endpoint is required");
        }
        if (to == null || to.isBlank()) {
            add(violations, WorkflowViolationCode.EDGE_ENDPOINT_REQUIRED, path + ".to", null, index,
                    "Edge to endpoint is required");
        }
        if (from != null && to != null && from.equals(to)) {
            add(violations, WorkflowViolationCode.SELF_EDGE, path, from, index,
                    "Self-edges are not allowed");
        }
        if (from != null && !nodesById.containsKey(from)) {
            add(violations, WorkflowViolationCode.UNKNOWN_NODE_REFERENCE, path + ".from", null, index,
                    "Edge references an unknown node");
        }
        if (to != null && !nodesById.containsKey(to)) {
            add(violations, WorkflowViolationCode.UNKNOWN_NODE_REFERENCE, path + ".to", null, index,
                    "Edge references an unknown node");
        }
        if (from != null && to != null && !edgeKeys.add(from + "\u0000" + to)) {
            add(violations, WorkflowViolationCode.DUPLICATE_EDGE, path, null, index,
                    "Parallel duplicate edges are not allowed");
        }

        if (nodesById.containsKey(from) && nodesById.containsKey(to)) {
            outgoing.get(from).add(to);
            outdegree.compute(from, (ignored, value) -> value + 1);
            indegree.compute(to, (ignored, value) -> value + 1);
        }
    }

    private void validateDegrees(
            Map<String, WorkflowNodeRequest> nodesById,
            List<WorkflowNodeRequest> sourceNodes,
            Map<String, WorkflowOperationCapability> capabilitiesByNode,
            Map<String, Integer> indegree,
            Map<String, Integer> outdegree,
            List<ApiViolationDetail> violations) {
        if (sourceNodes.size() == 1 && sourceNodes.get(0).getId() != null) {
            String sourceId = sourceNodes.get(0).getId();
            if (indegree.getOrDefault(sourceId, 0) != 0) {
                add(violations,
                        WorkflowViolationCode.SOURCE_INDEGREE_INVALID,
                        "$.graph.nodes",
                        sourceId,
                        null,
                        "SOURCE must have zero incoming edges");
            }
        }

        int terminalCount = 0;
        for (Map.Entry<String, WorkflowNodeRequest> entry : nodesById.entrySet()) {
            String id = entry.getKey();
            WorkflowNodeRequest node = entry.getValue();
            int in = indegree.getOrDefault(id, 0);
            int out = outdegree.getOrDefault(id, 0);
            if (out == 0) {
                terminalCount++;
            }
            if (out > 1) {
                add(violations,
                        WorkflowViolationCode.BRANCHING_NOT_ALLOWED,
                        "$.graph.nodes",
                        id,
                        null,
                        "A workflow node may have at most one outgoing edge");
            }
            if (in > 1) {
                add(violations,
                        WorkflowViolationCode.MERGING_NOT_ALLOWED,
                        "$.graph.nodes",
                        id,
                        null,
                        "A workflow node may have at most one incoming edge");
            }
            if (WorkflowNodeKind.TRANSFORM.name().equals(node.getKind()) && in != 1) {
                add(violations,
                        WorkflowViolationCode.TRANSFORM_INDEGREE_INVALID,
                        "$.graph.nodes",
                        id,
                        null,
                        "Every TRANSFORM must have exactly one incoming edge");
            }
            WorkflowOperationCapability capability = capabilitiesByNode.get(id);
            if (capability != null && capability.terminalOutput() && out > 0) {
                add(violations,
                        WorkflowViolationCode.TERMINAL_OUTPUT_HAS_SUCCESSOR,
                        "$.graph.nodes",
                        id,
                        null,
                        "A terminal operation output cannot have a successor");
            }
        }
        if (terminalCount != 1) {
            add(violations,
                    WorkflowViolationCode.TERMINAL_COUNT_INVALID,
                    "$.graph.nodes",
                    null,
                    null,
                    "Workflow must contain exactly one terminal node");
        }
    }

    private void validateEdgeModalities(
            List<WorkflowEdgeRequest> edges,
            Map<String, WorkflowNodeRequest> nodesById,
            List<ApiViolationDetail> violations) {
        for (int index = 0; index < edges.size(); index++) {
            WorkflowEdgeRequest edge = edges.get(index);
            if (edge == null) {
                continue;
            }
            WorkflowNodeRequest upstream = nodesById.get(edge.getFrom());
            WorkflowNodeRequest downstream = nodesById.get(edge.getTo());
            if (upstream == null || downstream == null) {
                continue;
            }
            String output = upstream.getOutputModality();
            String input = downstream.getInputModality();
            if (output != null && input != null && !output.equals(input)) {
                add(violations,
                        WorkflowViolationCode.EDGE_MODALITY_MISMATCH,
                        "$.graph.edges[" + index + "]",
                        downstream.getId(),
                        index,
                        "Upstream output modality must equal downstream input modality");
            }
        }
    }

    private void validateCycle(
            Set<String> nodeIds,
            Map<String, List<String>> outgoing,
            Map<String, Integer> indegree,
            List<ApiViolationDetail> violations) {
        Map<String, Integer> remainingIndegree = new LinkedHashMap<>(indegree);
        Deque<String> queue = new ArrayDeque<>();
        nodeIds.stream()
                .filter(id -> remainingIndegree.getOrDefault(id, 0) == 0)
                .forEach(queue::addLast);
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            visited++;
            for (String next : outgoing.getOrDefault(id, List.of())) {
                int updated = remainingIndegree.compute(next, (ignored, value) -> value - 1);
                if (updated == 0) {
                    queue.addLast(next);
                }
            }
        }
        if (visited != nodeIds.size()) {
            add(violations,
                    WorkflowViolationCode.CYCLE_DETECTED,
                    "$.graph",
                    null,
                    null,
                    "Workflow graph must not contain a cycle");
        }
    }

    private void validateConnectivity(
            Map<String, WorkflowNodeRequest> nodesById,
            List<WorkflowNodeRequest> sourceNodes,
            Map<String, List<String>> outgoing,
            List<ApiViolationDetail> violations) {
        if (sourceNodes.size() != 1 || sourceNodes.get(0).getId() == null
                || !nodesById.containsKey(sourceNodes.get(0).getId())) {
            return;
        }
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(sourceNodes.get(0).getId());
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!visited.add(id)) {
                continue;
            }
            outgoing.getOrDefault(id, List.of()).forEach(queue::addLast);
        }
        nodesById.keySet().stream()
                .filter(id -> !visited.contains(id))
                .forEach(id -> add(
                        violations,
                        WorkflowViolationCode.DISCONNECTED_NODE,
                        "$.graph.nodes",
                        id,
                        null,
                        "Every node must be reachable from SOURCE"));
    }

    private String normalizeRequiredName(
            String value,
            List<ApiViolationDetail> violations) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || normalized.isEmpty()) {
            add(violations, WorkflowViolationCode.NAME_REQUIRED, "$.name", null, null,
                    "Workflow name is required");
            return normalized;
        }
        if (codePointLength(normalized) > properties.getMaxNameChars()) {
            add(violations, WorkflowViolationCode.NAME_TOO_LONG, "$.name", null, null,
                    "Workflow name exceeds the configured character limit");
        }
        if (containsControlCharacter(normalized)) {
            add(violations,
                    WorkflowViolationCode.CONTROL_CHARACTER_NOT_ALLOWED,
                    "$.name",
                    null,
                    null,
                    "Workflow name contains a control character");
        }
        return normalized;
    }

    private String normalizeDescription(
            String value,
            List<ApiViolationDetail> violations) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        if (codePointLength(normalized) > properties.getMaxDescriptionChars()) {
            add(violations,
                    WorkflowViolationCode.DESCRIPTION_TOO_LONG,
                    "$.description",
                    null,
                    null,
                    "Workflow description exceeds the configured character limit");
        }
        if (containsControlCharacter(normalized)) {
            add(violations,
                    WorkflowViolationCode.CONTROL_CHARACTER_NOT_ALLOWED,
                    "$.description",
                    null,
                    null,
                    "Workflow description contains a control character");
        }
        return normalized;
    }

    private boolean isAllowedSourceModality(String value) {
        if (value == null) {
            return false;
        }
        try {
            return registry.sourceModalities().contains(WorkflowModality.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static WorkflowValidationResult result(
            String normalizedName,
            String normalizedDescription,
            WorkflowCanonicalization canonicalization,
            List<ApiViolationDetail> violations) {
        violations.sort(VIOLATION_ORDER);
        return new WorkflowValidationResult(
                violations.isEmpty(),
                normalizedName,
                normalizedDescription,
                canonicalization,
                violations);
    }

    private static void add(
            List<ApiViolationDetail> violations,
            WorkflowViolationCode code,
            String path,
            String nodeId,
            Integer edgeIndex,
            String message) {
        violations.add(new ApiViolationDetail(code.name(), path, nodeId, edgeIndex, message));
    }
}
